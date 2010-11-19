/*
 * Copyright (C) 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.android_scripting.facade;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

import android.app.Service;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.SurfaceHolder.Callback;

import com.googlecode.android_scripting.BaseApplication;
import com.googlecode.android_scripting.FutureActivityTaskExecutor;
import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.SingleThreadExecutor;
import com.googlecode.android_scripting.SimpleServer.SimpleServerObserver;
import com.googlecode.android_scripting.future.FutureActivityTask;
import com.googlecode.android_scripting.jsonrpc.RpcReceiver;
import com.googlecode.android_scripting.rpc.Rpc;
import com.googlecode.android_scripting.rpc.RpcDefault;
import com.googlecode.android_scripting.rpc.RpcParameter;

public class WebCamFacade extends RpcReceiver {

  private final Service mService;
  private final Executor mJpegCompressionExecutor = new SingleThreadExecutor();
  private final ByteArrayOutputStream mJpegCompressionBuffer = new ByteArrayOutputStream();

  private volatile byte[] mJpegData;

  private CountDownLatch mJpegDataReady;
  private boolean mStreaming;
  private int mPreviewHeight;
  private int mPreviewWidth;
  private int mJpegQuality;

  private MjpegServer mJpegServer;
  private FutureActivityTask<SurfaceHolder> mPreviewTask;
  private Camera mCamera;
  private Parameters mParameters;

  private final PreviewCallback mPreviewCallback = new PreviewCallback() {
    @Override
    public void onPreviewFrame(final byte[] data, final Camera camera) {
      mJpegCompressionExecutor.execute(new Runnable() {
        @Override
        public void run() {
          mJpegData = compressYuvToJpeg(data);
          mJpegDataReady.countDown();
          if (mStreaming) {
            camera.setOneShotPreviewCallback(mPreviewCallback);
          }
        }
      });
    }
  };

  public WebCamFacade(FacadeManager manager) {
    super(manager);
    mService = manager.getService();
    mJpegDataReady = new CountDownLatch(1);
  }

  private byte[] compressYuvToJpeg(final byte[] yuvData) {
    mJpegCompressionBuffer.reset();
    YuvImage yuvImage =
        new YuvImage(yuvData, ImageFormat.NV21, mPreviewWidth, mPreviewHeight, null);
    yuvImage.compressToJpeg(new Rect(0, 0, mPreviewWidth, mPreviewHeight), mJpegQuality,
        mJpegCompressionBuffer);
    return mJpegCompressionBuffer.toByteArray();
  }

  @Rpc(description = "Starts an MJPEG stream and returns a Tuple of address and port for the stream.")
  public InetSocketAddress webcamStart(
      @RpcParameter(name = "resolutionLevel", description = "increasing this number provides higher resolution") @RpcDefault("0") Integer resolutionLevel,
      @RpcParameter(name = "jpegQuality", description = "a number from 0-100") @RpcDefault("20") Integer jpegQuality,
      @RpcParameter(name = "port", description = "If port is specified, the webcam service will bind to port, otherwise it will pick any available port.") @RpcDefault("0") Integer port)
      throws Exception {
    try {
      openCamera(resolutionLevel, jpegQuality);
      return startServer(port);
    } catch (Exception e) {
      webcamStop();
      throw e;
    }
  }

  private InetSocketAddress startServer(Integer port) {
    mJpegServer = new MjpegServer(new JpegProvider() {
      @Override
      public byte[] getJpeg() {
        try {
          mJpegDataReady.await();
        } catch (InterruptedException e) {
          Log.e(e);
        }
        return mJpegData;
      }
    });
    mJpegServer.addObserver(new SimpleServerObserver() {
      @Override
      public void onDisconnect() {
        if (mJpegServer.getNumberOfConnections() == 0 && mStreaming) {
          stopStream();
        }
      }

      @Override
      public void onConnect() {
        if (!mStreaming) {
          startStream();
        }
      }
    });
    return mJpegServer.startPublic(port);
  }

  private void stopServer() {
    if (mJpegServer != null) {
      mJpegServer.shutdown();
      mJpegServer = null;
    }
  }

  @Rpc(description = "Adjusts the quality of the webcam stream while it is running.")
  public void webcamAdjustQuality(
      @RpcParameter(name = "resolutionLevel", description = "increasing this number provides higher resolution") @RpcDefault("0") Integer resolutionLevel,
      @RpcParameter(name = "jpegQuality", description = "a number from 0-100") @RpcDefault("20") Integer jpegQuality)
      throws Exception {
    if (mStreaming == false) {
      throw new IllegalStateException("Webcam not streaming.");
    }
    stopStream();
    releaseCamera();
    openCamera(resolutionLevel, jpegQuality);
    startStream();
  }

  private void openCamera(Integer resolutionLevel, Integer jpegQuality) throws IOException,
      InterruptedException {
    mCamera = Camera.open();
    mParameters = mCamera.getParameters();
    mParameters.setPictureFormat(ImageFormat.JPEG);
    mParameters.setPreviewFormat(ImageFormat.JPEG);
    List<Size> supportedPreviewSizes = mParameters.getSupportedPreviewSizes();
    Collections.sort(supportedPreviewSizes, new Comparator<Size>() {
      @Override
      public int compare(Size o1, Size o2) {
        return o1.width - o2.width;
      }
    });
    Size previewSize =
        supportedPreviewSizes.get(Math.min(resolutionLevel, supportedPreviewSizes.size() - 1));
    mPreviewHeight = previewSize.height;
    mPreviewWidth = previewSize.width;
    mParameters.setPreviewSize(mPreviewWidth, mPreviewHeight);
    mJpegQuality = Math.min(Math.max(jpegQuality, 0), 100);
    mCamera.setParameters(mParameters);
    // TODO(damonkohler): Rotate image based on orientation.
    mPreviewTask = createPreviewTask();
    mCamera.startPreview();
  }

  private void startStream() {
    mStreaming = true;
    mCamera.setOneShotPreviewCallback(mPreviewCallback);
  }

  private void stopStream() {
    mJpegDataReady = new CountDownLatch(1);
    mStreaming = false;
    if (mPreviewTask != null) {
      mPreviewTask.finish();
      mPreviewTask = null;
    }
  }

  private void releaseCamera() {
    if (mCamera != null) {
      mCamera.release();
      mCamera = null;
    }
    mParameters = null;
  }

  @Rpc(description = "Stops the webcam stream.")
  public void webcamStop() {
    stopServer();
    stopStream();
    releaseCamera();
  }

  private FutureActivityTask<SurfaceHolder> createPreviewTask() throws IOException,
      InterruptedException {
    FutureActivityTask<SurfaceHolder> task = new FutureActivityTask<SurfaceHolder>() {
      @Override
      public void onCreate() {
        super.onCreate();
        final SurfaceView view = new SurfaceView(getActivity());
        getActivity().setContentView(view);
        getActivity().getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        view.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        view.getHolder().addCallback(new Callback() {
          @Override
          public void surfaceDestroyed(SurfaceHolder holder) {
          }

          @Override
          public void surfaceCreated(SurfaceHolder holder) {
            setResult(view.getHolder());
          }

          @Override
          public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
          }
        });
      }
    };
    FutureActivityTaskExecutor taskExecutor =
        ((BaseApplication) mService.getApplication()).getTaskExecutor();
    taskExecutor.execute(task);
    mCamera.setPreviewDisplay(task.getResult());
    return task;
  }

  @Override
  public void shutdown() {
    webcamStop();
  }
}
