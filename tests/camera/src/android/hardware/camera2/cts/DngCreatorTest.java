/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.camera2.cts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.cts.helpers.StaticMetadata;
import android.hardware.camera2.cts.rs.BitmapUtils;
import android.hardware.camera2.cts.rs.RawConverter;
import android.hardware.camera2.cts.rs.RenderScriptSingleton;
import android.hardware.camera2.cts.testcases.Camera2AndroidTestCase;
import android.hardware.camera2.params.InputConfiguration;
import android.location.Location;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;

import com.android.ex.camera2.blocking.BlockingSessionCallback;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

import org.junit.runners.Parameterized;
import org.junit.runner.RunWith;
import org.junit.Test;

import static android.hardware.camera2.cts.helpers.AssertHelpers.*;
import static junit.framework.Assert.*;

/**
 * Tests for the DngCreator API.
 */

@RunWith(Parameterized.class)
public class DngCreatorTest extends Camera2AndroidTestCase {
    private static final String TAG = "DngCreatorTest";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);
    private static final String DEBUG_DNG_FILE = "raw16.dng";
    private static final String TEST_DNG_FILE = "test.dng";

    private static final double IMAGE_DIFFERENCE_TOLERANCE = 65;
    private static final int DEFAULT_PATCH_DIMEN = 512;
    private static final int AE_TIMEOUT_MS = 3000;
    private static final int MAX_RESOLUTION_CAPTURE_WAIT_TIMEOUT_SCALE = 4;
    private static final int MAX_RESOLUTION_CAPTURE_WAIT_TIMEOUT_MS =
            CAPTURE_WAIT_TIMEOUT_MS * MAX_RESOLUTION_CAPTURE_WAIT_TIMEOUT_SCALE;

    // Constants used for GPS testing.
    private static final double GPS_DIFFERENCE_TOLERANCE = 0.0001;
    private static final double GPS_LATITUDE = 37.420016;
    private static final double GPS_LONGITUDE = -122.081987;
    private static final String GPS_DATESTAMP = "2015:01:27";
    private static final String GPS_TIMESTAMP = "02:12:01";
    private static final Calendar GPS_CALENDAR =
            Calendar.getInstance(TimeZone.getTimeZone("GMT+0"));

    /** Load DNG validation jni on initialization */
    static {
        System.loadLibrary("ctscamera2_jni");
    }

    static {
        GPS_CALENDAR.set(2015, 0, 27, 2, 12, 01);
    }

    class CapturedData {
        public Pair<List<Image>, CaptureResult> imagePair;
        public CameraCharacteristics characteristics;
    }

    // CapturedData for maximum resolution mode, raw and jpeg don't share the same capture result
    // since mandatory streams for maximum resolution sensor pixel mode don't guarantee more than 1
    // stream.
    class CapturedDataMaximumResolution {
        public Pair<Image, CaptureResult> raw;
        public Pair<Image, CaptureResult> jpeg;
        public CameraCharacteristics characteristics;
    }

    class DngDebugParams {
        String deviceId;
        String intermediateStr;
        CameraCharacteristics characteristics;
        CaptureResult captureResult;
        FileOutputStream fileStream;
        FileChannel fileChannel;
        Image jpeg;
        Image raw;
        Bitmap rawBitmap;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        RenderScriptSingleton.setContext(mContext);
    }

    @Override
    public void tearDown() throws Exception {
        RenderScriptSingleton.clearContext();
        super.tearDown();
    }

    /**
     * Test basic raw capture and DNG saving functionality for each of the available cameras.
     *
     * <p>
     * For each camera, capture a single RAW16 image at the first capture size reported for
     * the raw format on that device, and save that image as a DNG file.  No further validation
     * is done.
     * </p>
     *
     * <p>
     * Note: Enabling adb shell setprop log.tag.DngCreatorTest VERBOSE will also cause the
     * raw image captured for the first reported camera device to be saved to an output file.
     * </p>
     */
    @Test
    public void testSingleImageBasic() throws Exception {
        for (int i = 0; i < mCameraIdsUnderTest.length; i++) {
            String deviceId = mCameraIdsUnderTest[i];
            ImageReader captureReader = null;
            FileOutputStream fileStream = null;
            ByteArrayOutputStream outputStream = null;
            try {
                if (!mAllStaticInfo.get(deviceId).isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    Log.i(TAG, "RAW capability is not supported in camera " + mCameraIdsUnderTest[i] +
                            ". Skip the test.");
                    continue;
                }

                openDevice(deviceId);
                Size activeArraySize = mStaticInfo.getRawDimensChecked();

                // Create capture image reader
                CameraTestUtils.SimpleImageReaderListener captureListener
                        = new CameraTestUtils.SimpleImageReaderListener();
                captureReader = createImageReader(activeArraySize, ImageFormat.RAW_SENSOR, 2,
                        captureListener);
                Pair<Image, CaptureResult> resultPair = captureSingleRawShot(activeArraySize,
                        /*waitForAe*/false, captureReader, captureListener);
                CameraCharacteristics characteristics = mStaticInfo.getCharacteristics();

                // Test simple writeImage, no header checks
                DngCreator dngCreator = new DngCreator(characteristics, resultPair.second);
                outputStream = new ByteArrayOutputStream();
                dngCreator.writeImage(outputStream, resultPair.first);

                if (VERBOSE) {
                    // Write DNG to file
                    String dngFilePath = mDebugFileNameBase + "/camera_basic_" + deviceId + "_" +
                            DEBUG_DNG_FILE;
                    // Write out captured DNG file for the first camera device if setprop is enabled
                    fileStream = new FileOutputStream(dngFilePath);
                    fileStream.write(outputStream.toByteArray());
                    fileStream.flush();
                    fileStream.close();
                    Log.v(TAG, "Test DNG file for camera " + deviceId + " saved to " + dngFilePath);
                }
                assertTrue("Generated DNG file does not pass validation",
                        validateDngNative(outputStream.toByteArray()));
            } finally {
                closeDevice(deviceId);
                closeImageReader(captureReader);

                if (outputStream != null) {
                    outputStream.close();
                }

                if (fileStream != null) {
                    fileStream.close();
                }
            }
        }
    }

    /**
     * Test basic maximum resolution raw capture and DNG saving functionality for each of the
     * available ultra high resolution cameras.
     *
     * <p>
     * For ultra high resolution each camera, capture a single RAW16 image at the first capture size
     * reported for the maximum resolution raw format on that device, and save that image as a DNG
     * file. No further validation is done.
     * </p>
     *
     * <p>
     * Note: Enabling adb shell setprop log.tag.DngCreatorTest VERBOSE will also cause the
     * raw image captured for the first reported camera device to be saved to an output file.
     * </p>
     */
    @Test
    public void testSingleImageBasicMaximumResolution() throws Exception {
        for (int i = 0; i < mCameraIdsUnderTest.length; i++) {
            String deviceId = mCameraIdsUnderTest[i];
            ImageReader captureReader = null;
            ImageReader reprocessCaptureReader = null;
            FileOutputStream fileStream = null;
            ByteArrayOutputStream outputStream = null;
            try {
                // All ultra high resolution sensors must necessarily support RAW
                if (!mAllStaticInfo.get(deviceId).isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
                    Log.i(TAG, "ULTRA_HIGH_RESOLUTION_SENSOR capability is not supported in " +
                            " camera " + mCameraIdsUnderTest[i] + ". Skip the test.");
                    continue;
                }

                openDevice(deviceId);
                Size activeArraySize = mStaticInfo.getRawDimensChecked(/*maxResolution*/true);

                // Create capture image reader
                CameraTestUtils.SimpleImageReaderListener captureReaderListener
                        = new CameraTestUtils.SimpleImageReaderListener();
                CameraTestUtils.SimpleImageReaderListener reprocessReaderListener
                        = new CameraTestUtils.SimpleImageReaderListener();

                captureReader = createImageReader(activeArraySize, ImageFormat.RAW_SENSOR, 2,
                        captureReaderListener);

                reprocessCaptureReader = createImageReader(activeArraySize, ImageFormat.RAW_SENSOR,
                        2, reprocessReaderListener);
                Pair<Image, CaptureResult> resultPair = null;
                if (mAllStaticInfo.get(deviceId).isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING)) {
                    resultPair =
                            captureReprocessedRawShot(activeArraySize, captureReader,
                                    reprocessCaptureReader, captureReaderListener,
                                    reprocessReaderListener, /*waitForAe*/false);
                } else {
                    resultPair = captureSingleShotMaximumResolution(activeArraySize,
                            captureReader, /*waitForAe*/false, captureReaderListener);
                }
                CameraCharacteristics characteristics = mStaticInfo.getCharacteristics();

                // Test simple writeImage, no header checks
                DngCreator dngCreator = new DngCreator(characteristics, resultPair.second);
                outputStream = new ByteArrayOutputStream();
                dngCreator.writeImage(outputStream, resultPair.first);

                if (VERBOSE) {
                    // Write DNG to file
                    String dngFilePath = mDebugFileNameBase + "/camera_basic_max_resolution_" +
                            deviceId + "_" + DEBUG_DNG_FILE;
                    // Write out captured DNG file for the first camera device if setprop is enabled
                    fileStream = new FileOutputStream(dngFilePath);
                    fileStream.write(outputStream.toByteArray());
                    fileStream.flush();
                    fileStream.close();
                    Log.v(TAG, "Test DNG file for camera " + deviceId + " saved to " + dngFilePath);
                }
                assertTrue("Generated DNG file does not pass validation",
                        validateDngNative(outputStream.toByteArray()));
            } finally {
                closeDevice(deviceId);
                closeImageReader(captureReader);
                closeImageReader(reprocessCaptureReader);

                if (outputStream != null) {
                    outputStream.close();
                }

                if (fileStream != null) {
                    fileStream.close();
                }
            }
        }
    }

    /**
     * Test basic raw capture and DNG saving with a thumbnail, rotation, usercomment, and GPS tags
     * set.
     *
     * <p>
     * For each camera, capture a single RAW16 image at the first capture size reported for
     * the raw format on that device, and save that image as a DNG file. GPS information validation
     * is done via ExifInterface.
     * </p>
     *
     * <p>
     * Note: Enabling adb shell setprop log.tag.DngCreatorTest VERBOSE will also cause the
     * raw image captured for the first reported camera device to be saved to an output file.
     * </p>
     */
    @Test
    public void testSingleImageThumbnail() throws Exception {
        for (int i = 0; i < mCameraIdsUnderTest.length; i++) {
            String deviceId = mCameraIdsUnderTest[i];
            List<ImageReader> captureReaders = new ArrayList<ImageReader>();
            List<CameraTestUtils.SimpleImageReaderListener> captureListeners =
                    new ArrayList<CameraTestUtils.SimpleImageReaderListener>();
            FileOutputStream fileStream = null;
            ByteArrayOutputStream outputStream = null;
            try {
                if (!mAllStaticInfo.get(deviceId).isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    Log.i(TAG, "RAW capability is not supported in camera " + mCameraIdsUnderTest[i] +
                            ". Skip the test.");
                    continue;
                }

                openDevice(deviceId);
                Size activeArraySize = mStaticInfo.getRawDimensChecked();

                Size[] targetPreviewSizes =
                        mStaticInfo.getAvailableSizesForFormatChecked(ImageFormat.YUV_420_888,
                                StaticMetadata.StreamDirection.Output);
                // Get smallest preview size
                Size previewSize = mOrderedPreviewSizes.get(mOrderedPreviewSizes.size() - 1);

                // Create capture image reader
                CameraTestUtils.SimpleImageReaderListener captureListener
                        = new CameraTestUtils.SimpleImageReaderListener();
                captureReaders.add(createImageReader(activeArraySize, ImageFormat.RAW_SENSOR, 2,
                        captureListener));
                captureListeners.add(captureListener);

                CameraTestUtils.SimpleImageReaderListener previewListener
                        = new CameraTestUtils.SimpleImageReaderListener();

                captureReaders.add(createImageReader(previewSize, ImageFormat.YUV_420_888, 2,
                        previewListener));
                captureListeners.add(previewListener);

                Date beforeCaptureDate = new Date();
                Pair<List<Image>, CaptureResult> resultPair = captureSingleRawShot(activeArraySize,
                        captureReaders, /*waitForAe*/false, captureListeners);
                Date afterCaptureDate = new Date();
                CameraCharacteristics characteristics = mStaticInfo.getCharacteristics();

                if (VERBOSE) {
                    Log.v(TAG, "Sensor timestamp (ms): " +
                            resultPair.second.get(CaptureResult.SENSOR_TIMESTAMP) / 1000000);
                    Log.v(TAG, "SystemClock.elapsedRealtimeNanos (ms): " +
                            SystemClock.elapsedRealtimeNanos() / 1000000);
                    Log.v(TAG, "SystemClock.uptimeMillis(): " + SystemClock.uptimeMillis());
                }
                // Test simple writeImage, no header checks
                DngCreator dngCreator = new DngCreator(characteristics, resultPair.second);
                Location l = new Location("test");
                l.reset();
                l.setLatitude(GPS_LATITUDE);
                l.setLongitude(GPS_LONGITUDE);
                l.setTime(GPS_CALENDAR.getTimeInMillis());
                dngCreator.setLocation(l);

                dngCreator.setDescription("helloworld");
                dngCreator.setOrientation(ExifInterface.ORIENTATION_FLIP_VERTICAL);
                dngCreator.setThumbnail(resultPair.first.get(1));
                outputStream = new ByteArrayOutputStream();
                dngCreator.writeImage(outputStream, resultPair.first.get(0));

                String filePath = mDebugFileNameBase + "/camera_thumb_" + deviceId + "_" +
                        DEBUG_DNG_FILE;
                // Write out captured DNG file for the first camera device
                fileStream = new FileOutputStream(filePath);
                fileStream.write(outputStream.toByteArray());
                fileStream.flush();
                fileStream.close();
                if (VERBOSE) {
                    Log.v(TAG, "Test DNG file for camera " + deviceId + " saved to " + filePath);
                }

                assertTrue("Generated DNG file does not pass validation",
                        validateDngNative(outputStream.toByteArray()));

                ExifInterface exifInterface = new ExifInterface(filePath);
                // Verify GPS data.
                float[] latLong = new float[2];
                assertTrue(exifInterface.getLatLong(latLong));
                assertEquals(GPS_LATITUDE, latLong[0], GPS_DIFFERENCE_TOLERANCE);
                assertEquals(GPS_LONGITUDE, latLong[1], GPS_DIFFERENCE_TOLERANCE);
                assertEquals(GPS_DATESTAMP,
                        exifInterface.getAttribute(ExifInterface.TAG_GPS_DATESTAMP));
                assertEquals(GPS_TIMESTAMP,
                        exifInterface.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP));

                // Verify the orientation.
                assertEquals(ExifInterface.ORIENTATION_FLIP_VERTICAL,
                        exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_UNDEFINED));

                // Verify the date/time
                final SimpleDateFormat dngDateTimeStampFormat =
                        new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                dngDateTimeStampFormat.setLenient(false);

                String dateTimeString =
                        exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
                assertTrue(dateTimeString != null);

                Date dateTime = dngDateTimeStampFormat.parse(dateTimeString);
                long captureTimeMs = dateTime.getTime();

                Log.i(TAG, "DNG DateTime tag: " + dateTimeString);
                Log.i(TAG, "Before capture time: " + beforeCaptureDate.getTime());
                Log.i(TAG, "Capture time: " + captureTimeMs);
                Log.i(TAG, "After capture time: " + afterCaptureDate.getTime());

                // Offset beforeCaptureTime by 1 second to account for rounding down of
                // DNG tag
                long beforeCaptureTimeMs = beforeCaptureDate.getTime() - 1000;
                long afterCaptureTimeMs = afterCaptureDate.getTime();
                assertTrue(captureTimeMs >= beforeCaptureTimeMs);
                assertTrue(captureTimeMs <= afterCaptureTimeMs);

                if (!VERBOSE) {
                    // Delete the captured DNG file.
                    File dngFile = new File(filePath);
                    assertTrue(dngFile.delete());
                }
            } finally {
                closeDevice(deviceId);
                for (ImageReader r : captureReaders) {
                    closeImageReader(r);
                }

                if (outputStream != null) {
                    outputStream.close();
                }

                if (fileStream != null) {
                    fileStream.close();
                }
            }
        }
    }

    /**
     * Test basic maximum resolution RAW capture, and ensure that the rendered RAW output is
     * similar to the maximum resolution JPEG created for a similar frame.
     *
     * Since mandatory streams for maximum resolution sensor pixel mode do not guarantee 2 maximum
     * resolution streams we can't capture RAW + JPEG images of the same frame. Therefore, 2
     * sessions are created, one for RAW capture and the other for JPEG capture.
     *
     * <p>
     * This test renders the RAW buffer into an RGB bitmap using a rendering pipeline
     * similar to one in the Adobe DNG validation tool.  JPEGs produced by the vendor hardware may
     * have different tonemapping and saturation applied than the RGB bitmaps produced
     * from this DNG rendering pipeline, and this test allows for fairly wide variations
     * between the histograms for the RAW and JPEG buffers to avoid false positives.
     * </p>
     *
     * <p>
     * To ensure more subtle errors in the colorspace transforms returned for the HAL's RAW
     * metadata, the DNGs and JPEGs produced here should also be manually compared using external
     * DNG rendering tools.  The DNG, rendered RGB bitmap, and JPEG buffer for this test can be
     * dumped to the SD card for further examination by enabling the 'verbose' mode for this test
     * using:
     * adb shell setprop log.tag.DngCreatorTest VERBOSE
     * </p>
     */
    @Test
    public void testRaw16JpegMaximumResolutionConsistency() throws Exception {
        for (String deviceId : mCameraIdsUnderTest) {
            ImageReader rawImageReader = null;
            ImageReader jpegImageReader = null;
            FileOutputStream fileStream = null;
            FileChannel fileChannel = null;
            try {
                // All ultra high resolution sensors must necessarily support RAW
                if (!mAllStaticInfo.get(deviceId).isCapabilitySupported(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
                    Log.i(TAG, "ULTRA_HIGH_RESOLUTION_SENSOR capability is not supported in " +
                            " camera " + deviceId + ". Skip " +
                            "testRaw16JpegMaximumResolutionConsistency");
                    continue;
                }

                CapturedDataMaximumResolution data =
                        captureRawJpegImagePairMaximumResolution(deviceId, rawImageReader,
                                jpegImageReader);
                if (data == null) {
                    continue;
                }
                Image raw = data.raw.first;
                Image jpeg = data.jpeg.first;

                Bitmap rawBitmap = Bitmap.createBitmap(raw.getWidth(), raw.getHeight(),
                        Bitmap.Config.ARGB_8888);

                byte[] rawPlane = new byte[raw.getPlanes()[0].getRowStride() * raw.getHeight()];

                // Render RAW image to a bitmap
                raw.getPlanes()[0].getBuffer().get(rawPlane);
                raw.getPlanes()[0].getBuffer().rewind();

                RawConverter.convertToSRGB(RenderScriptSingleton.getRS(), raw.getWidth(),
                        raw.getHeight(), raw.getPlanes()[0].getRowStride(), rawPlane,
                        data.characteristics, /*captureREsult*/data.raw.second, /*offsetX*/ 0,
                        /*offsetY*/ 0, /*out*/ rawBitmap);

                rawPlane = null;
                System.gc(); // Hint to VM

                if (VERBOSE) {
                    DngDebugParams params = new DngDebugParams();
                    params.deviceId = deviceId;
                    params.characteristics = data.characteristics;
                    params.captureResult = data.raw.second;
                    params.fileStream = fileStream;
                    params.raw = raw;
                    params.jpeg = jpeg;
                    params.fileChannel = fileChannel;
                    params.rawBitmap = rawBitmap;
                    params.intermediateStr = "maximum_resolution_";

                    debugDumpDng(params);
                }

                validateRawJpegImagePair(rawBitmap, jpeg, deviceId);
            } finally {
                closeImageReader(rawImageReader);
                closeImageReader(jpegImageReader);

                if (fileChannel != null) {
                    fileChannel.close();
                }
                if (fileStream != null) {
                    fileStream.close();
                }
            }
        }
    }



    /**
     * Test basic RAW capture, and ensure that the rendered RAW output is similar to the JPEG
     * created for the same frame.
     *
     * <p>
     * This test renders the RAW buffer into an RGB bitmap using a rendering pipeline
     * similar to one in the Adobe DNG validation tool.  JPEGs produced by the vendor hardware may
     * have different tonemapping and saturation applied than the RGB bitmaps produced
     * from this DNG rendering pipeline, and this test allows for fairly wide variations
     * between the histograms for the RAW and JPEG buffers to avoid false positives.
     * </p>
     *
     * <p>
     * To ensure more subtle errors in the colorspace transforms returned for the HAL's RAW
     * metadata, the DNGs and JPEGs produced here should also be manually compared using external
     * DNG rendering tools.  The DNG, rendered RGB bitmap, and JPEG buffer for this test can be
     * dumped to the SD card for further examination by enabling the 'verbose' mode for this test
     * using:
     * adb shell setprop log.tag.DngCreatorTest VERBOSE
     * </p>
     */
    @Test
    public void testRaw16JpegConsistency() throws Exception {
        for (String deviceId : mCameraIdsUnderTest) {
            List<ImageReader> captureReaders = new ArrayList<>();
            FileOutputStream fileStream = null;
            FileChannel fileChannel = null;
            try {
                CapturedData data = captureRawJpegImagePair(deviceId, captureReaders);
                if (data == null) {
                    continue;
                }
                Image raw = data.imagePair.first.get(0);
                Image jpeg = data.imagePair.first.get(1);

                Bitmap rawBitmap = Bitmap.createBitmap(raw.getWidth(), raw.getHeight(),
                        Bitmap.Config.ARGB_8888);

                byte[] rawPlane = new byte[raw.getPlanes()[0].getRowStride() * raw.getHeight()];

                // Render RAW image to a bitmap
                raw.getPlanes()[0].getBuffer().get(rawPlane);
                raw.getPlanes()[0].getBuffer().rewind();

                RawConverter.convertToSRGB(RenderScriptSingleton.getRS(), raw.getWidth(),
                        raw.getHeight(), raw.getPlanes()[0].getRowStride(), rawPlane,
                        data.characteristics, data.imagePair.second, /*offsetX*/ 0, /*offsetY*/ 0,
                        /*out*/ rawBitmap);

                rawPlane = null;
                System.gc(); // Hint to VM

                if (VERBOSE) {
                    DngDebugParams params = new DngDebugParams();
                    params.deviceId = deviceId;
                    params.characteristics = data.characteristics;
                    params.captureResult = data.imagePair.second;
                    params.fileStream = fileStream;
                    params.raw = raw;
                    params.jpeg = jpeg;
                    params.fileChannel = fileChannel;
                    params.rawBitmap = rawBitmap;
                    params.intermediateStr = "";

                    debugDumpDng(params);
                }

                validateRawJpegImagePair(rawBitmap, jpeg, deviceId);
            } finally {
                for (ImageReader r : captureReaders) {
                    closeImageReader(r);
                }

                if (fileChannel != null) {
                    fileChannel.close();
                }

                if (fileStream != null) {
                    fileStream.close();
                }
            }
        }
    }

    /**
     * Test basic DNG creation, ensure that the DNG image can be rendered by BitmapFactory.
     */
    @Test
    public void testDngRenderingByBitmapFactor() throws Exception {
        for (String deviceId : mCameraIdsUnderTest) {
            List<ImageReader> captureReaders = new ArrayList<>();

            CapturedData data = captureRawJpegImagePair(deviceId, captureReaders);
            if (data == null) {
                continue;
            }
            Image raw = data.imagePair.first.get(0);
            Image jpeg = data.imagePair.first.get(1);

            // Generate DNG file
            DngCreator dngCreator = new DngCreator(data.characteristics, data.imagePair.second);

            // Write DNG to file
            String dngFilePath = mDebugFileNameBase + "/camera_" +
                deviceId + "_" + TEST_DNG_FILE;

            // Write out captured DNG file for the first camera device if setprop is enabled
            try (FileOutputStream fileStream = new FileOutputStream(dngFilePath)) {
                dngCreator.writeImage(fileStream, raw);

                // Render the DNG file using BitmapFactory.
                Bitmap rawBitmap = BitmapFactory.decodeFile(dngFilePath);
                assertNotNull(rawBitmap);

                validateRawJpegImagePair(rawBitmap, jpeg, deviceId);
            } finally {
                for (ImageReader r : captureReaders) {
                    closeImageReader(r);
                }

                System.gc(); // Hint to VM
            }
        }
    }

    /*
     * Create RAW + JPEG image pair with characteristics info.
     */
    private CapturedData captureRawJpegImagePair(String deviceId, List<ImageReader> captureReaders)
            throws Exception {
        CapturedData data = new CapturedData();
        List<CameraTestUtils.SimpleImageReaderListener> captureListeners = new ArrayList<>();
        try {
            if (!mAllStaticInfo.get(deviceId).isCapabilitySupported(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                Log.i(TAG, "RAW capability is not supported in camera " + deviceId
                        + ". Skip the test.");
                return null;
            }

            openDevice(deviceId);
            Size activeArraySize = mStaticInfo.getRawDimensChecked();

            // Get largest jpeg size
            Size[] targetJpegSizes = mStaticInfo.getAvailableSizesForFormatChecked(
                    ImageFormat.JPEG, StaticMetadata.StreamDirection.Output);

            Size largestJpegSize = Collections.max(Arrays.asList(targetJpegSizes),
                    new CameraTestUtils.SizeComparator());

            // Create raw image reader and capture listener
            CameraTestUtils.SimpleImageReaderListener rawListener =
                    new CameraTestUtils.SimpleImageReaderListener();
            captureReaders.add(createImageReader(activeArraySize, ImageFormat.RAW_SENSOR, 2,
                    rawListener));
            captureListeners.add(rawListener);


            // Create jpeg image reader and capture listener
            CameraTestUtils.SimpleImageReaderListener jpegListener =
                    new CameraTestUtils.SimpleImageReaderListener();
            captureReaders.add(createImageReader(largestJpegSize, ImageFormat.JPEG, 2,
                    jpegListener));
            captureListeners.add(jpegListener);

            data.imagePair = captureSingleRawShot(activeArraySize,
                    captureReaders, /*waitForAe*/ true, captureListeners);
            data.characteristics = mStaticInfo.getCharacteristics();

            Image raw = data.imagePair.first.get(0);
            Size rawBitmapSize = new Size(raw.getWidth(), raw.getHeight());
            assertTrue("Raw bitmap size must be equal to either pre-correction active array" +
                    " size or pixel array size.", rawBitmapSize.equals(activeArraySize));

            return data;
        } finally {
            closeDevice(deviceId);
        }
    }

   private void debugDumpDng(DngDebugParams params) throws Exception {
        // Generate DNG file
        DngCreator dngCreator =
                new DngCreator(params.characteristics, params.captureResult);

        // Write DNG to file
        String dngFilePath = mDebugFileNameBase + "/camera_" + params.intermediateStr +
                params.deviceId + "_" + DEBUG_DNG_FILE;
        // Write out captured DNG file for the first camera device if setprop is enabled
        params.fileStream = new FileOutputStream(dngFilePath);
        dngCreator.writeImage(params.fileStream, params.raw);
        params.fileStream.flush();
        params.fileStream.close();
        Log.v(TAG, "Test DNG file for camera " + params.deviceId + " saved to " + dngFilePath);

        // Write JPEG to file
        String jpegFilePath = mDebugFileNameBase + "/camera_" + params.intermediateStr  +
                params.deviceId + "_jpeg.jpg";
        // Write out captured DNG file for the first camera device if setprop is enabled
        params.fileChannel = new FileOutputStream(jpegFilePath).getChannel();
        ByteBuffer jPlane = params.jpeg.getPlanes()[0].getBuffer();
        params.fileChannel.write(jPlane);
        params.fileChannel.close();
        jPlane.rewind();
        Log.v(TAG, "Test JPEG file for camera " + params.deviceId + " saved to " +
                jpegFilePath);

        // Write jpeg generated from demosaiced RAW frame to file
        String rawFilePath = mDebugFileNameBase + "/camera_" + params.intermediateStr +
                params.deviceId + "_raw.jpg";
        // Write out captured DNG file for the first camera device if setprop is enabled
        params.fileStream = new FileOutputStream(rawFilePath);
        params.rawBitmap.compress(Bitmap.CompressFormat.JPEG, 90, params.fileStream);
        params.fileStream.flush();
        params.fileStream.close();
        Log.v(TAG, "Test converted RAW file for camera " + params.deviceId + " saved to " +
                rawFilePath);
   }

    /*
     * Create RAW + JPEG image pair with characteristics info. Assumes the device supports the RAW
     * capability.
     */
    private CapturedDataMaximumResolution captureRawJpegImagePairMaximumResolution(String deviceId,
            ImageReader rawCaptureReader, ImageReader jpegCaptureReader)
            throws Exception {
        CapturedDataMaximumResolution data = new CapturedDataMaximumResolution();
        try {

            openDevice(deviceId);
            Size activeArraySize = mStaticInfo.getRawDimensChecked(/*maxResolution*/true);

            // Get largest jpeg size
            Size[] targetJpegSizes = mStaticInfo.getAvailableSizesForFormatChecked(
                    ImageFormat.JPEG, StaticMetadata.StreamDirection.Output, /*fastSizes*/ true,
                    /*slowSizes*/ true, /*maxResolution*/true);

            Size largestJpegSize = Collections.max(Arrays.asList(targetJpegSizes),
                    new CameraTestUtils.SizeComparator());

            // Create raw image reader and capture listener
            CameraTestUtils.SimpleImageReaderListener rawCaptureReaderListener =
                    new CameraTestUtils.SimpleImageReaderListener();
            rawCaptureReader = createImageReader(activeArraySize, ImageFormat.RAW_SENSOR, 2,
                    rawCaptureReaderListener);

            // Create jpeg image reader and capture listener
            CameraTestUtils.SimpleImageReaderListener jpegCaptureListener =
                    new CameraTestUtils.SimpleImageReaderListener();
            jpegCaptureReader = createImageReader(largestJpegSize, ImageFormat.JPEG, 2,
                    jpegCaptureListener);

            Pair<Image, CaptureResult> jpegResultPair =
                    captureSingleShotMaximumResolution(activeArraySize,
                             jpegCaptureReader, /*waitForAe*/true, jpegCaptureListener);
            data.jpeg = jpegResultPair;
            data.characteristics = mStaticInfo.getCharacteristics();
            // Create capture image reader
            CameraTestUtils.SimpleImageReaderListener outputRawCaptureReaderListener
                    = new CameraTestUtils.SimpleImageReaderListener();
            CameraTestUtils.SimpleImageReaderListener reprocessReaderListener
                    = new CameraTestUtils.SimpleImageReaderListener();

            ImageReader outputRawCaptureReader = createImageReader(activeArraySize,
                    ImageFormat.RAW_SENSOR, 2, outputRawCaptureReaderListener);
            Pair<Image, CaptureResult> rawResultPair = null;
            if (mAllStaticInfo.get(deviceId).isCapabilitySupported(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING)) {
                rawResultPair =
                        captureReprocessedRawShot(activeArraySize, outputRawCaptureReader,
                                    rawCaptureReader, outputRawCaptureReaderListener,
                                    reprocessReaderListener, /*waitForAe*/ true);
            } else {
                rawResultPair = captureSingleShotMaximumResolution(activeArraySize,
                        rawCaptureReader, /*waitForAe*/true, rawCaptureReaderListener);
            }
            data.raw = rawResultPair;
            Size rawBitmapSize =
                    new Size(rawResultPair.first.getWidth(), rawResultPair.first.getHeight());
            assertTrue("Raw bitmap size must be equal to either pre-correction active array" +
                    " size or pixel array size.", rawBitmapSize.equals(activeArraySize));

            return data;
        } finally {
            closeDevice(deviceId);
        }
    }

    /*
     * Verify the image pair by comparing the center patch.
     */
    private void validateRawJpegImagePair(Bitmap rawBitmap, Image jpeg, String deviceId)
            throws Exception {
        // Decompress JPEG image to a bitmap
        byte[] compressedJpegData = CameraTestUtils.getDataFromImage(jpeg);

        // Get JPEG dimensions without decoding
        BitmapFactory.Options opt0 = new BitmapFactory.Options();
        opt0.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(compressedJpegData, /*offset*/0,
                compressedJpegData.length, /*inout*/opt0);
        Rect jpegDimens = new Rect(0, 0, opt0.outWidth, opt0.outHeight);

        // Find square center patch from JPEG and RAW bitmaps
        RectF jpegRect = new RectF(jpegDimens);
        RectF rawRect = new RectF(0, 0, rawBitmap.getWidth(), rawBitmap.getHeight());
        int sideDimen = Math.min(Math.min(Math.min(Math.min(DEFAULT_PATCH_DIMEN,
                jpegDimens.width()), jpegDimens.height()), rawBitmap.getWidth()),
                rawBitmap.getHeight());

        RectF jpegIntermediate = new RectF(0, 0, sideDimen, sideDimen);
        jpegIntermediate.offset(jpegRect.centerX() - jpegIntermediate.centerX(),
                jpegRect.centerY() - jpegIntermediate.centerY());

        RectF rawIntermediate = new RectF(0, 0, sideDimen, sideDimen);
        rawIntermediate.offset(rawRect.centerX() - rawIntermediate.centerX(),
                rawRect.centerY() - rawIntermediate.centerY());
        Rect jpegFinal = new Rect();
        jpegIntermediate.roundOut(jpegFinal);
        Rect rawFinal = new Rect();
        rawIntermediate.roundOut(rawFinal);

        // Get RAW center patch, and free up rest of RAW image
        Bitmap rawPatch = Bitmap.createBitmap(rawBitmap, rawFinal.left, rawFinal.top,
                rawFinal.width(), rawFinal.height());
        rawBitmap.recycle();
        rawBitmap = null;
        System.gc(); // Hint to VM

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap jpegPatch = BitmapRegionDecoder.newInstance(compressedJpegData,
                /*offset*/0, compressedJpegData.length, /*isShareable*/true).
                decodeRegion(jpegFinal, opt);

        // Compare center patch from JPEG and rendered RAW bitmap
        double difference = BitmapUtils.calcDifferenceMetric(jpegPatch, rawPatch);
        if (difference > IMAGE_DIFFERENCE_TOLERANCE) {
            FileOutputStream fileStream = null;
            try {
                // Write JPEG patch to file
                String jpegFilePath = mDebugFileNameBase + "/camera_" + deviceId +
                        "_jpeg_patch.jpg";
                fileStream = new FileOutputStream(jpegFilePath);
                jpegPatch.compress(Bitmap.CompressFormat.JPEG, 90, fileStream);
                fileStream.flush();
                fileStream.close();
                Log.e(TAG, "Failed JPEG patch file for camera " + deviceId + " saved to " +
                        jpegFilePath);

                // Write RAW patch to file
                String rawFilePath = mDebugFileNameBase + "/camera_" + deviceId +
                        "_raw_patch.jpg";
                fileStream = new FileOutputStream(rawFilePath);
                rawPatch.compress(Bitmap.CompressFormat.JPEG, 90, fileStream);
                fileStream.flush();
                fileStream.close();
                Log.e(TAG, "Failed RAW patch file for camera " + deviceId + " saved to " +
                        rawFilePath);

                fail("Camera " + deviceId + ": RAW and JPEG image at  for the same " +
                        "frame are not similar, center patches have difference metric of " +
                        difference);
            } finally {
                if (fileStream != null) {
                    fileStream.close();
                }
            }
        }
    }

    private Pair<Image, CaptureResult> captureSingleRawShot(Size s, boolean waitForAe,
            ImageReader captureReader,
            CameraTestUtils.SimpleImageReaderListener captureListener) throws Exception {
        List<ImageReader> readers = new ArrayList<ImageReader>();
        readers.add(captureReader);
        List<CameraTestUtils.SimpleImageReaderListener> listeners =
                new ArrayList<CameraTestUtils.SimpleImageReaderListener>();
        listeners.add(captureListener);
        Pair<List<Image>, CaptureResult> res = captureSingleRawShot(s, readers, waitForAe,
                listeners);
        return new Pair<Image, CaptureResult>(res.first.get(0), res.second);
    }

    private Pair<List<Image>, CaptureResult> captureSingleRawShot(Size s,
            List<ImageReader> captureReaders, boolean waitForAe,
            List<CameraTestUtils.SimpleImageReaderListener> captureListeners) throws Exception {
        return captureRawShots(s, captureReaders, waitForAe, captureListeners, 1,
                /*maxResolution*/false).get(0);
    }

    private Pair<Image, CaptureResult> captureSingleShotMaximumResolution(Size s,
            ImageReader captureReader, boolean waitForAe,
            CameraTestUtils.SimpleImageReaderListener captureListener)
            throws Exception {
        List<ImageReader> readers = new ArrayList<ImageReader>();
        readers.add(captureReader);
        List<CameraTestUtils.SimpleImageReaderListener> listeners =
                new ArrayList<CameraTestUtils.SimpleImageReaderListener>();
        listeners.add(captureListener);
        Pair<List<Image>, CaptureResult> res = captureRawShots(s, readers, waitForAe,
                listeners, /*numShots*/ 1, /*maxResolution*/ true).get(0);
        return new Pair<Image, CaptureResult>(res.first.get(0), res.second);
    }

    private Pair<Image, CaptureResult> captureReprocessedRawShot(Size sz,
            ImageReader inputReader,
            ImageReader reprocessOutputReader,
            CameraTestUtils.SimpleImageReaderListener inputReaderListener,
            CameraTestUtils.SimpleImageReaderListener reprocessReaderListener,
            boolean waitForAe) throws Exception {

        InputConfiguration inputConfig =
            new InputConfiguration(sz.getWidth(), sz.getHeight(), ImageFormat.RAW_SENSOR);
        CameraTestUtils.SimpleCaptureCallback inputCaptureListener =
                new CameraTestUtils.SimpleCaptureCallback();
        CameraTestUtils.SimpleCaptureCallback reprocessOutputCaptureListener =
                new CameraTestUtils.SimpleCaptureCallback();

        inputReader.setOnImageAvailableListener(inputReaderListener, mHandler);
        reprocessOutputReader.setOnImageAvailableListener(reprocessReaderListener, mHandler);

        ArrayList<Surface> outputSurfaces = new ArrayList<Surface>();
        outputSurfaces.add(inputReader.getSurface());
        outputSurfaces.add(reprocessOutputReader.getSurface());
        BlockingSessionCallback sessionListener = new BlockingSessionCallback();
        ImageReader previewReader = null;
        if (waitForAe) {
            // Also setup a small YUV output for AE metering if needed
            Size yuvSize = (mOrderedPreviewSizes.size() == 0) ? null :
                    mOrderedPreviewSizes.get(mOrderedPreviewSizes.size() - 1);
            assertNotNull("Must support at least one small YUV size.", yuvSize);
            previewReader = createImageReader(yuvSize, ImageFormat.YUV_420_888,
                        /*maxNumImages*/2, new CameraTestUtils.ImageDropperListener());
            outputSurfaces.add(previewReader.getSurface());
        }

        createReprocessableSession(inputConfig, outputSurfaces);

        if (waitForAe) {
            CaptureRequest.Builder precaptureRequest =
                    mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            assertNotNull("Fail to get captureRequest", precaptureRequest);
            precaptureRequest.addTarget(previewReader.getSurface());
            precaptureRequest.set(CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_AUTO);
            precaptureRequest.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            final ConditionVariable waitForAeCondition = new ConditionVariable(/*isOpen*/false);
            CameraCaptureSession.CaptureCallback captureCallback =
                    new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(CameraCaptureSession session,
                        CaptureRequest request, CaptureResult partialResult) {
                    Integer aeState = partialResult.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState != null &&
                            (aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED ||
                             aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED)) {
                        waitForAeCondition.open();
                    }
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                        CaptureRequest request, TotalCaptureResult result) {
                    int aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        waitForAeCondition.open();
                    }
                }
            };

            startCapture(precaptureRequest.build(), /*repeating*/true, captureCallback, mHandler);

            precaptureRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            startCapture(precaptureRequest.build(), /*repeating*/false, captureCallback, mHandler);
            assertTrue("Timeout out waiting for AE to converge",
                    waitForAeCondition.block(AE_TIMEOUT_MS));
        }
        ImageWriter inputWriter =
                ImageWriter.newInstance(mCameraSession.getInputSurface(), 1);
        // Prepare a request for reprocess input
        CaptureRequest.Builder builder = mCamera.createCaptureRequest(
                CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
        builder.addTarget(inputReader.getSurface());
        // This is a max resolution capture
        builder.set(CaptureRequest.SENSOR_PIXEL_MODE,
                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        CaptureRequest inputRequest = builder.build();
        mCameraSession.capture(inputRequest, inputCaptureListener, mHandler);
        List<CaptureRequest> reprocessCaptureRequests = new ArrayList<>();

        TotalCaptureResult inputResult =
                inputCaptureListener.getTotalCaptureResult(
                        MAX_RESOLUTION_CAPTURE_WAIT_TIMEOUT_MS);
        builder = mCamera.createReprocessCaptureRequest(inputResult);
        inputWriter.queueInputImage(inputReaderListener.getImage(
                        MAX_RESOLUTION_CAPTURE_WAIT_TIMEOUT_MS));
        builder.addTarget(reprocessOutputReader.getSurface());
        reprocessCaptureRequests.add(builder.build());
        mCameraSession.captureBurst(reprocessCaptureRequests, reprocessOutputCaptureListener,
                mHandler);
        TotalCaptureResult result = reprocessOutputCaptureListener.getTotalCaptureResult(
                CAPTURE_WAIT_TIMEOUT_MS);
        return new Pair<Image, CaptureResult>(reprocessReaderListener.getImage(
                MAX_RESOLUTION_CAPTURE_WAIT_TIMEOUT_MS), result);
    }

    /**
     * Capture raw images.
     *
     * <p>Capture raw images for a given size.</p>
     *
     * @param sz The size of the raw image to capture.  Must be one of the available sizes for this
     *          device.
     *
     * @param captureReaders The image readers which are associated with the targets for this
     *        capture.
     *
     * @param waitForAe Whether we should wait for AE to converge before capturing outputs for
     *                  the captureReaders targets
     *
     * @param captureListeners ImageReader listeners which wait on the captured images to be
     *                         available.
     *
     * @param numShots The number of shots to be captured
     *
     * @param maxResolution Whether the target in captureReaders are max resolution captures. If
     *                      this is set to true, captureReaders.size() must be == 1 ( in order to
     *                      satisfy mandatory streams for maximum resolution sensor pixel mode).
     *
     * @return a list of pairs containing a {@link Image} and {@link CaptureResult} used for
     *          each capture.
     */
    private List<Pair<List<Image>, CaptureResult>> captureRawShots(Size sz,
            List<ImageReader> captureReaders, boolean waitForAe,
            List<CameraTestUtils.SimpleImageReaderListener> captureListeners,
            int numShots, boolean maxResolution) throws Exception {
        if (VERBOSE) {
            Log.v(TAG, "captureSingleRawShot - Capturing raw image.");
        }

        int timeoutScale = maxResolution ? MAX_RESOLUTION_CAPTURE_WAIT_TIMEOUT_SCALE : 1;
        Size[] targetCaptureSizes =
                mStaticInfo.getAvailableSizesForFormatChecked(ImageFormat.RAW_SENSOR,
                        StaticMetadata.StreamDirection.Output, /*fastSizes*/ true,
                        /*slowSizes*/ true, maxResolution);

        if (maxResolution) {
            assertTrue("Maximum number of maximum resolution targets for a session should be 1 as" +
                " per the mandatory streams guarantee", captureReaders.size() == 1);
        }

        // Validate size
        boolean validSize = false;
        for (int i = 0; i < targetCaptureSizes.length; ++i) {
            if (targetCaptureSizes[i].equals(sz)) {
                validSize = true;
                break;
            }
        }
        assertTrue("Capture size is supported.", validSize);

        // Capture images.
        final List<Surface> outputSurfaces = new ArrayList<Surface>();
        for (ImageReader captureReader : captureReaders) {
            Surface captureSurface = captureReader.getSurface();
            outputSurfaces.add(captureSurface);
        }

        // Set up still capture template targeting JPEG/RAW outputs
        CaptureRequest.Builder request =
                mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        assertNotNull("Fail to get captureRequest", request);
        for (Surface surface : outputSurfaces) {
            request.addTarget(surface);
        }

        ImageReader previewReader = null;
        if (waitForAe) {
            // Also setup a small YUV output for AE metering if needed
            Size yuvSize = (mOrderedPreviewSizes.size() == 0) ? null :
                    mOrderedPreviewSizes.get(mOrderedPreviewSizes.size() - 1);
            assertNotNull("Must support at least one small YUV size.", yuvSize);
            previewReader = createImageReader(yuvSize, ImageFormat.YUV_420_888,
                        /*maxNumImages*/2, new CameraTestUtils.ImageDropperListener());
            outputSurfaces.add(previewReader.getSurface());
        }

        createSession(outputSurfaces);

        if (waitForAe) {
            CaptureRequest.Builder precaptureRequest =
                    mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            assertNotNull("Fail to get captureRequest", precaptureRequest);
            precaptureRequest.addTarget(previewReader.getSurface());
            precaptureRequest.set(CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_AUTO);
            precaptureRequest.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);

            final ConditionVariable waitForAeCondition = new ConditionVariable(/*isOpen*/false);
            CameraCaptureSession.CaptureCallback captureCallback =
                    new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(CameraCaptureSession session,
                        CaptureRequest request, CaptureResult partialResult) {
                    Integer aeState = partialResult.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState != null &&
                            (aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED ||
                             aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED)) {
                        waitForAeCondition.open();
                    }
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                        CaptureRequest request, TotalCaptureResult result) {
                    int aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        waitForAeCondition.open();
                    }
                }
            };
            startCapture(precaptureRequest.build(), /*repeating*/true, captureCallback, mHandler);

            precaptureRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            startCapture(precaptureRequest.build(), /*repeating*/false, captureCallback, mHandler);
            assertTrue("Timeout out waiting for AE to converge",
                    waitForAeCondition.block(AE_TIMEOUT_MS));
        }

        request.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        if (maxResolution) {
            request.set(CaptureRequest.SENSOR_PIXEL_MODE,
                    CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        }
        CameraTestUtils.SimpleCaptureCallback resultListener =
                new CameraTestUtils.SimpleCaptureCallback();

        CaptureRequest request1 = request.build();
        for (int i = 0; i < numShots; i++) {
            startCapture(request1, /*repeating*/false, resultListener, mHandler);
        }
        List<Pair<List<Image>, CaptureResult>> ret = new ArrayList<>();
        for (int i = 0; i < numShots; i++) {
            // Verify capture result and images
            CaptureResult result = resultListener.getCaptureResult(CAPTURE_WAIT_TIMEOUT_MS);

            List<Image> resultImages = new ArrayList<Image>();
            for (CameraTestUtils.SimpleImageReaderListener captureListener : captureListeners) {
                Image captureImage =
                        captureListener.getImage(CAPTURE_WAIT_TIMEOUT_MS * timeoutScale);

            /*CameraTestUtils.validateImage(captureImage, s.getWidth(), s.getHeight(),
                    ImageFormat.RAW_SENSOR, null);*/
                resultImages.add(captureImage);
            }
            ret.add(new Pair<List<Image>, CaptureResult>(resultImages, result));
        }
        // Stop capture, delete the streams.
        stopCapture(/*fast*/false);

        return ret;
    }

    /**
     * Use the DNG SDK to validate a DNG file stored in the buffer.
     *
     * Returns false if the DNG has validation errors. Validation warnings/errors
     * will be printed to logcat.
     */
    private static native boolean validateDngNative(byte[] dngBuffer);
}
