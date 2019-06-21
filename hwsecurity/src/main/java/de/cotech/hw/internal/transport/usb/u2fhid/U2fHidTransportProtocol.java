/*
 * Copyright (C) 2018-2019 Confidential Technologies GmbH
 *
 * You can purchase a commercial license at https://hwsecurity.dev.
 * Buying such a license is mandatory as soon as you develop commercial
 * activities involving this program without disclosing the source code
 * of your own applications.
 *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.cotech.hw.internal.transport.usb.u2fhid;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import de.cotech.hw.internal.transport.usb.UsbTransportException;
import de.cotech.hw.internal.transport.usb.u2fhid.U2fHidInitStructFactory.U2fHidInitResponse;
import timber.log.Timber;


@RestrictTo(Scope.LIBRARY_GROUP)
public class U2fHidTransportProtocol {
    @NonNull
    private final U2fHidInitStructFactory initStructFactory = new U2fHidInitStructFactory(new SecureRandom());
    @NonNull
    private final U2fHidFrameFactory frameFactory = new U2fHidFrameFactory();
    @NonNull
    private final UsbDeviceConnection usbCconnection;
    @NonNull
    private final UsbEndpoint usbEndpointIn;
    @NonNull
    private final UsbEndpoint usbEndpointOut;
    @NonNull
    private final ByteBuffer transferBuffer;
    @NonNull
    private final ExecutorService executor;

    private int channelId = U2fHidFrameFactory.U2FHID_CHANNEL_ID_BROADCAST;

    U2fHidTransportProtocol(@NonNull UsbDeviceConnection usbCconnection,
            @NonNull UsbEndpoint usbEndpointIn, @NonNull UsbEndpoint usbEndpointOut) {
        // noinspection ConstantConditions, checking method contract
        if (usbCconnection == null) {
            throw new NullPointerException();
        }
        // noinspection ConstantConditions, checking method contract
        if (usbEndpointIn == null) {
            throw new NullPointerException();
        }
        // noinspection ConstantConditions, checking method contract
        if (usbEndpointOut == null) {
            throw new NullPointerException();
        }

        this.usbCconnection = usbCconnection;
        this.usbEndpointIn = usbEndpointIn;
        this.usbEndpointOut = usbEndpointOut;
        // Allocating a direct buffer here *will break* on some android devices!
        this.transferBuffer = ByteBuffer.allocate(U2fHidFrameFactory.U2FHID_BUFFER_SIZE);
        this.executor = Executors.newSingleThreadExecutor();
    }

    @WorkerThread
    public void connect() throws UsbTransportException {
        Timber.d("Initializing U2FHID transport…");

        this.channelId = negotiateChannelId();
    }

    private int negotiateChannelId() throws UsbTransportException {
        byte[] initRequestBytes = initStructFactory.createInitRequest();
        byte[] requestFrame = frameFactory.wrapFrame(channelId, U2fHidFrameFactory.U2FHID_INIT, initRequestBytes);
        writeHidPacketsToUsbDevice(requestFrame);

        return performUsbRequestWithTimeout(usbRequest -> {
            if (!usbRequest.initialize(usbCconnection, usbEndpointIn)) {
                throw new IOException("Read request could not be opened!");
            }

            while (true) {
                transferBuffer.clear();
                if (!usbRequest.queue(transferBuffer, U2fHidFrameFactory.U2FHID_BUFFER_SIZE)) {
                    throw new IOException("Failed to receive data!");
                }
                usbCconnection.requestWait();
                try {
                    byte[] response = frameFactory.unwrapFrame(channelId, U2fHidFrameFactory.U2FHID_INIT, transferBuffer.array());
                    U2fHidInitResponse initResponse = initStructFactory.parseInitResponse(response, initRequestBytes);

                    Timber.d("U2FHID_INIT response: %s", initResponse);
                    return initResponse.channelId();
                } catch (UsbTransportException e) {
                    Timber.d("Ignoring unrelated INIT response");
                }
            }
        }, 850);
    }

    @WorkerThread
    byte[] transceive(byte[] payload) throws UsbTransportException {
        byte[] requestFrame = frameFactory.wrapFrame(channelId, U2fHidFrameFactory.U2FHID_MSG, payload);
        writeHidPacketsToUsbDevice(requestFrame);

        byte[] responseFrame = readHidPacketsFromUsbDevice();
        return frameFactory.unwrapFrame(channelId, U2fHidFrameFactory.U2FHID_MSG, responseFrame);
    }

    @WorkerThread
    private byte[] readHidPacketsFromUsbDevice() throws UsbTransportException {
        return performUsbRequestWithTimeout(usbRequest -> {
            if (!usbRequest.initialize(usbCconnection, usbEndpointIn)) {
                throw new IOException("Read request could not be opened!");
            }

            if (!usbRequest.queue(transferBuffer, U2fHidFrameFactory.U2FHID_BUFFER_SIZE)) {
                throw new IOException("Failed to receive data!");
            }
            usbCconnection.requestWait();

            transferBuffer.clear();
            int expectedFrames = frameFactory.findExpectedFramesFromInitPacketHeader(channelId, transferBuffer);

            byte[] data = new byte[expectedFrames * U2fHidFrameFactory.U2FHID_BUFFER_SIZE];
            transferBuffer.clear();
            transferBuffer.get(data, 0, U2fHidFrameFactory.U2FHID_BUFFER_SIZE);

            int offset = U2fHidFrameFactory.U2FHID_BUFFER_SIZE;
            for (int i = 1; i < expectedFrames; i++) {
                if (!usbRequest.queue(transferBuffer, U2fHidFrameFactory.U2FHID_BUFFER_SIZE)) {
                    throw new IOException("Failed to receive data!");
                }
                usbCconnection.requestWait();
                transferBuffer.clear();
                transferBuffer.get(data, offset, U2fHidFrameFactory.U2FHID_BUFFER_SIZE);
                offset += U2fHidFrameFactory.U2FHID_BUFFER_SIZE;
            }

            return data;
        }, 2 * 1000);
    }

    @WorkerThread
    private void writeHidPacketsToUsbDevice(byte[] hidFrame) throws UsbTransportException {
        if ((hidFrame.length % U2fHidFrameFactory.U2FHID_BUFFER_SIZE) != 0) {
            throw new IllegalArgumentException("Invalid HID frame size!");
        }

        performUsbRequestWithTimeout(usbRequest -> {
            if (!usbRequest.initialize(usbCconnection, usbEndpointOut)) {
                throw new IOException("Request could not be opened!");
            }

            int offset = 0;
            while (offset < hidFrame.length) {
                transferBuffer.clear();
                transferBuffer.put(hidFrame, offset, U2fHidFrameFactory.U2FHID_BUFFER_SIZE);
                if (!usbRequest.queue(transferBuffer, U2fHidFrameFactory.U2FHID_BUFFER_SIZE)) {
                    throw new IOException("Failed to send data!");
                }
                usbCconnection.requestWait(); // blocking
                offset += U2fHidFrameFactory.U2FHID_BUFFER_SIZE;
            }

            return null;
        }, 1000);
    }

    @WorkerThread
    private <T> T performUsbRequestWithTimeout(UsbRequestTask<T> task, int timeoutMs) throws UsbTransportException {
        UsbRequest usbRequest = newUsbRequest();
        try {
            Future<T> future = executor.submit(() -> task.performUsbRequest(usbRequest));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            usbRequest.cancel();
            throw new UsbTransportException("Error transmitting data!", e);
        } finally {
            usbRequest.close();
        }
    }

    @VisibleForTesting
    UsbRequest newUsbRequest() {
        return new UsbRequest();
    }

    @VisibleForTesting
    int getChannelId() {
        return channelId;
    }

    interface UsbRequestTask<T> {
        @WorkerThread
        T performUsbRequest(UsbRequest request) throws IOException;
    }
}