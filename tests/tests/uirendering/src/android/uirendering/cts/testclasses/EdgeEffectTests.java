/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.uirendering.cts.testclasses;

import static android.uirendering.cts.util.MockVsyncHelper.nextFrame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.compat.CompatChanges;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.uirendering.cts.R;
import android.uirendering.cts.bitmapverifiers.ColorVerifier;
import android.uirendering.cts.bitmapverifiers.PerPixelBitmapVerifier;
import android.uirendering.cts.bitmapverifiers.RegionVerifier;
import android.uirendering.cts.testinfrastructure.ActivityTestBase;
import android.uirendering.cts.testinfrastructure.Tracer;
import android.uirendering.cts.util.BitmapAsserter;
import android.uirendering.cts.util.MockVsyncHelper;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.EdgeEffect;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class EdgeEffectTests extends ActivityTestBase {
    static final long USE_STRETCH_EDGE_EFFECT_BY_DEFAULT = 171228096L;
    static final long USE_STRETCH_EDGE_EFFECT_FOR_SUPPORTED = 178807038L;

    private static final int WIDTH = 90;
    private static final int HEIGHT = 90;

    @Rule
    public Tracer name = new Tracer();

    private BitmapAsserter mBitmapAsserter = new BitmapAsserter(this.getClass().getSimpleName(),
            name.getMethodName());

    private Context mThemeContext;

    interface EdgeEffectInitializer {
        void initialize(EdgeEffect edgeEffect);
    }

    private Context getContext() {
        return mThemeContext;
    }

    @Before
    public void setUp() {
        final Context targetContext = InstrumentationRegistry.getTargetContext();
        mThemeContext = new ContextThemeWrapper(targetContext,
                android.R.style.Theme_Material_Light);
    }

    private static class EdgeEffectValidator extends PerPixelBitmapVerifier {
        public int matchedColorCount;

        private int mInverseColorMask;
        private int mColorMask;

        public EdgeEffectValidator(int drawColor) {
            mColorMask = drawColor & 0x00FFFFFF;
            mInverseColorMask = ~(drawColor & 0x00FFFFFF);
        }

        @Override
        protected boolean verifyPixel(int x, int y, int observedColor) {
            if ((observedColor & mColorMask) != 0) {
                matchedColorCount++;
            }
            return (observedColor & mInverseColorMask) == 0xFF000000;
        }
    }

    private void assertEdgeEffect(EdgeEffectInitializer initializer) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        EdgeEffect edgeEffect = new EdgeEffect(getContext());
        edgeEffect.setSize(WIDTH, HEIGHT);
        edgeEffect.setColor(Color.RED);
        edgeEffect.setType(EdgeEffect.TYPE_GLOW);
        assertEquals(Color.RED, edgeEffect.getColor());
        initializer.initialize(edgeEffect);
        edgeEffect.draw(canvas);

        EdgeEffectValidator verifier = new EdgeEffectValidator(edgeEffect.getColor());
        mBitmapAsserter.assertBitmapIsVerified(bitmap, verifier,
                name.getMethodName(), "EdgeEffect doesn't match expected");
        assertTrue(verifier.matchedColorCount > 0);
    }

    @Test
    public void testOnPull() {
        assertEdgeEffect(edgeEffect -> {
            edgeEffect.onPull(1);
        });
    }

    @Test
    public void testSetSize() {
        assertEdgeEffect(edgeEffect -> {
            edgeEffect.setSize(70, 70);
            edgeEffect.onPull(1);
        });
    }

    @Test
    public void testSetColor() {
        assertEdgeEffect(edgeEffect -> {
            edgeEffect.setColor(Color.GREEN);
            assertEquals(Color.GREEN, edgeEffect.getColor());
            edgeEffect.onPull(1);
        });
    }

    @Test
    public void testSetBlendMode() {
        assertEdgeEffect(edgeEffect -> {
            edgeEffect.setBlendMode(null);
            assertNull(edgeEffect.getBlendMode());
            edgeEffect.setBlendMode(EdgeEffect.DEFAULT_BLEND_MODE);
            assertEquals(BlendMode.SRC_ATOP, edgeEffect.getBlendMode());
            edgeEffect.onPull(1);
        });
    }

    @Test
    public void testOnPullWithDisplacement() {
        assertEdgeEffect(edgeEffect -> {
            edgeEffect.onPull(1, 0);
        });

        assertEdgeEffect(edgeEffect -> {
            edgeEffect.onPull(1, 1);
        });
    }

    @Test
    public void testIsFinished() {
        EdgeEffect effect = new EdgeEffect(getContext());
        assertTrue(effect.isFinished());
        effect.onPull(0.5f);
        assertFalse(effect.isFinished());
    }

    @Test
    public void testFinish() {
        EdgeEffect effect = new EdgeEffect(getContext());
        effect.onPull(1);
        effect.finish();
        assertTrue(effect.isFinished());

        effect.onAbsorb(1000);
        effect.finish();
        assertFalse(effect.draw(new Canvas()));
    }

    @Test
    public void testGetColor() {
        EdgeEffect effect = new EdgeEffect(getContext());
        effect.setColor(Color.GREEN);
        assertEquals(Color.GREEN, effect.getColor());
    }

    @Test
    public void testGetMaxHeight() {
        EdgeEffect edgeEffect = new EdgeEffect(getContext());
        edgeEffect.setSize(200, 200);
        assertTrue(edgeEffect.getMaxHeight() <= 200 * 2 + 1);
        edgeEffect.setSize(200, 0);
        assertEquals(0, edgeEffect.getMaxHeight());
    }

    @Test
    public void testEdgeEffectTypeAccessors() {
        EdgeEffect effect = new EdgeEffect(getContext());

        int expectedStartType = (CompatChanges.isChangeEnabled(USE_STRETCH_EDGE_EFFECT_BY_DEFAULT)
                || CompatChanges.isChangeEnabled(USE_STRETCH_EDGE_EFFECT_FOR_SUPPORTED))
                ? EdgeEffect.TYPE_STRETCH : EdgeEffect.TYPE_GLOW;
        assertEquals(expectedStartType, effect.getType());
        effect.setType(EdgeEffect.TYPE_STRETCH);
        assertEquals(EdgeEffect.TYPE_STRETCH, effect.getType());
    }

    @Test
    public void testEdgeEffectTypeAttribute() {
        final Context targetContext = InstrumentationRegistry.getTargetContext();
        final Context themeContext =
                new ContextThemeWrapper(targetContext, R.style.StretchEdgeEffect);
        EdgeEffect withWarpEffect = new EdgeEffect(themeContext);
        assertEquals(EdgeEffect.TYPE_STRETCH, withWarpEffect.getType());
    }

    @Test
    public void testCustomViewEdgeEffectAttribute() {
        Context targetContext = InstrumentationRegistry.getTargetContext();
        LayoutInflater layoutInflater = LayoutInflater.from(targetContext);
        View view = layoutInflater.inflate(R.layout.stretch_edge_effect_view, null);
        assertTrue(view instanceof CustomEdgeEffectView);
        CustomEdgeEffectView customEdgeEffectView = (CustomEdgeEffectView) view;
        assertEquals(EdgeEffect.TYPE_STRETCH, customEdgeEffectView.edgeEffect.getType());
    }

    @Test
    public void testDistance() {
        EdgeEffect effect = new EdgeEffect(getContext());

        assertEquals(0f, effect.getDistance(), 0.001f);

        assertEquals(0.1f, effect.onPullDistance(0.1f, 0.5f), 0.001f);

        assertEquals(0.1f, effect.getDistance(), 0.001f);

        assertEquals(-0.05f, effect.onPullDistance(-0.05f, 0.5f), 0.001f);

        assertEquals(0.05f, effect.getDistance(), 0.001f);

        assertEquals(-0.05f, effect.onPullDistance(-0.2f, 0.5f), 0.001f);

        assertEquals(0f, effect.getDistance(), 0.001f);
    }

    private RenderNode drawStretchEffect(float distance, float displacement, float rotation) {
        int width = WIDTH;
        int height = HEIGHT;
        EdgeEffect edgeEffect = new EdgeEffect(getContext());
        edgeEffect.setSize(width, height);
        edgeEffect.setType(EdgeEffect.TYPE_STRETCH);
        edgeEffect.onPullDistance(distance, displacement);

        RenderNode renderNode = new RenderNode("");
        renderNode.setPosition(0, 0, width, height);
        RecordingCanvas recordingCanvas = renderNode.beginRecording();
        Paint paint = new Paint();
        paint.setColor(Color.GREEN);
        recordingCanvas.drawRect(0f, 0f, width, height / 2f, paint);
        paint.setColor(Color.MAGENTA);
        recordingCanvas.drawRect(0, height / 2f, width, height, paint);
        renderNode.endRecording();

        RenderNode outer = new RenderNode("outer");
        outer.setPosition(0, 0, width, height);
        RecordingCanvas outerRecordingCanvas = outer.beginRecording();
        outerRecordingCanvas.drawRenderNode(renderNode);
        recordingCanvas.rotate(rotation, width / 2f, height / 2f);
        edgeEffect.draw(outerRecordingCanvas);
        outer.endRecording();
        return outer;
    }

    @Test
    public void testStretchTop() {
        RenderNode renderNode = drawStretchEffect(1f, 1f, 0f);
        Rect innerRect = new Rect(0, 0, WIDTH, HEIGHT / 2 + 1);
        Rect outerRect = new Rect(0, HEIGHT / 2 + 5, WIDTH, HEIGHT);
        createTest()
                .addCanvasClientWithoutUsingPicture((canvas, width, height) -> {
                    canvas.drawRenderNode(renderNode);
                }, true)
                .runWithVerifier(
                        new RegionVerifier().addVerifier(
                                innerRect,
                                new ColorVerifier(Color.GREEN)
                        ).addVerifier(
                                outerRect,
                                new ColorVerifier(Color.MAGENTA)
                        ));
    }

    @Test
    public void testStretchBottom() {
        RenderNode renderNode = drawStretchEffect(1f, 1f, 180f);
        Rect innerRect = new Rect(0, 0, WIDTH, 1);
        Rect outerRect = new Rect(0, (HEIGHT / 2) - 1, WIDTH, HEIGHT / 2);
        createTest()
                .addCanvasClientWithoutUsingPicture((canvas, width, height) -> {
                    canvas.drawRenderNode(renderNode);
                }, true)
                .runWithVerifier(
                        new RegionVerifier().addVerifier(
                                innerRect,
                                new ColorVerifier(Color.GREEN)
                        ).addVerifier(
                                outerRect,
                                new ColorVerifier(Color.MAGENTA)
                        ));
    }

    @Test
    public void testNoSetSizeCallDoesNotCrash() {
        EdgeEffect edgeEffect = new EdgeEffect(getContext());
        edgeEffect.setType(EdgeEffect.TYPE_STRETCH);
        edgeEffect.onPullDistance(1f, 1f);
        edgeEffect.onAbsorb(100);
        edgeEffect.onRelease();

        RenderNode node = new RenderNode("");
        RecordingCanvas canvas = node.beginRecording();
        edgeEffect.draw(canvas);
        node.endRecording();
    }

    @Test
    public void testAbsorbThenDrawDoesNotCrash() {
        MockVsyncHelper.runOnVsyncThread(() -> {
            EdgeEffect edgeEffect = new EdgeEffect(getContext());
            edgeEffect.setType(EdgeEffect.TYPE_STRETCH);
            edgeEffect.onPullDistance(1f, 1f);
            edgeEffect.onAbsorb(100);
            edgeEffect.onRelease();

            nextFrame();

            edgeEffect.setSize(10, 10);
            RenderNode node = new RenderNode("");
            node.setPosition(0, 0, TEST_WIDTH, TEST_HEIGHT);
            RecordingCanvas canvas = node.beginRecording();
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            canvas.drawRect(0f, 0f, TEST_WIDTH, TEST_HEIGHT, paint);

            canvas.rotate(90, TEST_WIDTH / 2f, TEST_HEIGHT / 2f);
            edgeEffect.draw(canvas);
            node.endRecording();
        });
    }

    /**
     * When a TYPE_STRETCH is used, a held pull should not retract.
     */
    @Test
    @LargeTest
    public void testStretchPullAndHold() throws Exception {
        EdgeEffect edgeEffect = createEdgeEffectWithPull(EdgeEffect.TYPE_STRETCH);
        assertEquals(0.25f, edgeEffect.getDistance(), 0.001f);

        // We must wait until the EdgeEffect would normally start receding (167 ms)
        sleepAnimationTime(200);

        // Drawing will cause updates of the distance if it is animating
        RenderNode renderNode = new RenderNode(null);
        Canvas canvas = renderNode.beginRecording();
        edgeEffect.draw(canvas);

        // A glow effect would start receding now, so let's be sure it doesn't:
        sleepAnimationTime(200);
        edgeEffect.draw(canvas);

        // It should not be updating now
        assertEquals(0.25f, edgeEffect.getDistance(), 0.001f);

        // Now let's release it and it should start animating
        edgeEffect.onRelease();

        sleepAnimationTime(20);

        // Now that it should be animating, the draw should update the distance
        edgeEffect.draw(canvas);

        assertTrue(edgeEffect.getDistance() < 0.25f);
    }

    /**
     * When a TYPE_GLOW is used, a held pull should retract after the timeout.
     */
    @Test
    @LargeTest
    public void testGlowPullAndHold() throws Exception {
        EdgeEffect edgeEffect = createEdgeEffectWithPull(EdgeEffect.TYPE_GLOW);
        assertEquals(0.25f, edgeEffect.getDistance(), 0.001f);

        // We must wait until the EdgeEffect would normally start receding (167 ms)
        sleepAnimationTime(200);

        // Drawing will cause updates of the distance if it is animating
        RenderNode renderNode = new RenderNode(null);
        Canvas canvas = renderNode.beginRecording();
        edgeEffect.draw(canvas);

        // It should start retracting now:
        sleepAnimationTime(20);
        edgeEffect.draw(canvas);
        assertTrue(edgeEffect.getDistance() < 0.25f);
    }

    /**
     * It should be possible to catch the stretch effect during an animation.
     */
    @Test
    @LargeTest
    public void testCatchStretchDuringAnimation() throws Exception {
        EdgeEffect edgeEffect = createEdgeEffectWithPull(EdgeEffect.TYPE_STRETCH);
        assertEquals(0.25f, edgeEffect.getDistance(), 0.001f);
        edgeEffect.onRelease();

        // Wait some time to be sure it is animating away.
        long startTime = AnimationUtils.currentAnimationTimeMillis();
        sleepAnimationTime(20);

        // Drawing will cause updates of the distance if it is animating
        RenderNode renderNode = new RenderNode(null);
        Canvas canvas = renderNode.beginRecording();
        edgeEffect.draw(canvas);

        // It should have started retracting. Now catch it.
        float consumed = edgeEffect.onPullDistance(0f, 0.5f);
        assertEquals(0f, consumed, 0f);

        float distanceAfterAnimation = edgeEffect.getDistance();
        assertTrue(distanceAfterAnimation < 0.25f);


        sleepAnimationTime(50);

        // There should be no change once it has been caught.
        edgeEffect.draw(canvas);
        assertEquals(distanceAfterAnimation, edgeEffect.getDistance(), 0f);
    }

    /**
     * It should be possible to catch the glow effect during an animation.
     */
    @Test
    @LargeTest
    public void testCatchGlowDuringAnimation() throws Exception {
        EdgeEffect edgeEffect = createEdgeEffectWithPull(EdgeEffect.TYPE_GLOW);
        edgeEffect.onRelease();

        // Wait some time to be sure it is animating away.
        long startTime = AnimationUtils.currentAnimationTimeMillis();
        sleepAnimationTime(20);

        // Drawing will cause updates of the distance if it is animating
        RenderNode renderNode = new RenderNode(null);
        Canvas canvas = renderNode.beginRecording();
        edgeEffect.draw(canvas);

        // It should have started retracting. Now catch it.
        float consumed = edgeEffect.onPullDistance(0f, 0.5f);
        assertEquals(0f, consumed, 0f);

        float distanceAfterAnimation = edgeEffect.getDistance();
        assertTrue(distanceAfterAnimation < 0.25f);


        sleepAnimationTime(50);

        // There should be no change once it has been caught.
        edgeEffect.draw(canvas);
        assertEquals(distanceAfterAnimation, edgeEffect.getDistance(), 0f);
    }

    /**
     * When an EdgeEffect with TYPE_STRETCH is drawn on a non-RecordingCanvas, the animation
     * should immediately end.
     */
    @Test
    public void testStretchOnBitmapCanvas() throws Throwable {
        EdgeEffect edgeEffect = createEdgeEffectWithPull(EdgeEffect.TYPE_STRETCH);
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        edgeEffect.draw(canvas);
        assertTrue(edgeEffect.isFinished());
        assertEquals(0f, edgeEffect.getDistance(), 0f);
    }

    private EdgeEffect createEdgeEffectWithPull(int edgeEffectType) {
        EdgeEffect edgeEffect = new EdgeEffect(getContext());
        edgeEffect.setType(edgeEffectType);
        edgeEffect.setSize(100, 100);
        edgeEffect.onPullDistance(0.25f, 0.5f);
        return edgeEffect;
    }

    /**
     * This sleeps until the {@link AnimationUtils#currentAnimationTimeMillis()} changes
     * by at least <code>durationMillis</code> milliseconds. This is useful for EdgeEffect because
     * it uses that mechanism to determine the animation duration.
     *
     * @param durationMillis The time to sleep in milliseconds.
     */
    private void sleepAnimationTime(long durationMillis) throws Exception {
        final long startTime = AnimationUtils.currentAnimationTimeMillis();
        long currentTime = startTime;
        final long endTime = startTime + durationMillis;
        do {
            Thread.sleep(endTime - currentTime);
            currentTime = AnimationUtils.currentAnimationTimeMillis();
        } while (currentTime < endTime);
    }

    private interface AlphaVerifier {
        void verify(int oldAlpha, int newAlpha);
    }

    // validates changes to the alpha of draw commands produced by EdgeEffect
    // over the course of an animation
    private void verifyAlpha(EdgeEffectInitializer initializer, AlphaVerifier alphaVerifier) {
        MockVsyncHelper.runOnVsyncThread(() -> {
            Canvas canvas = mock(Canvas.class);
            ArgumentCaptor<Paint> captor = ArgumentCaptor.forClass(Paint.class);
            EdgeEffect edgeEffect = new EdgeEffect(getContext());
            edgeEffect.setSize(200, 200);
            edgeEffect.setType(EdgeEffect.TYPE_GLOW);
            initializer.initialize(edgeEffect);
            edgeEffect.draw(canvas);
            verify(canvas).drawCircle(anyFloat(), anyFloat(), anyFloat(), captor.capture());
            int oldAlpha = captor.getValue().getAlpha();
            for (int i = 0; i < 3; i++) {
                nextFrame();
                canvas = mock(Canvas.class);
                edgeEffect.draw(canvas);
                verify(canvas).drawCircle(anyFloat(), anyFloat(), anyFloat(), captor.capture());
                int newAlpha = captor.getValue().getAlpha();
                alphaVerifier.verify(oldAlpha, newAlpha);
                oldAlpha = newAlpha;
            }
        });
    }

    @Test
    public void testOnAbsorb() {
        verifyAlpha(edgeEffect -> {
            edgeEffect.onAbsorb(10000);
        }, ((oldAlpha, newAlpha) -> {
            assertTrue("Alpha should grow", oldAlpha < newAlpha);
        }));
    }

    @Test
    public void testOnRelease() {
        verifyAlpha(edgeEffect -> {
            edgeEffect.onPull(1);
            edgeEffect.onRelease();
        }, ((oldAlpha, newAlpha) -> {
            assertTrue("Alpha should decrease", oldAlpha > newAlpha);
        }));
    }

    public static class CustomEdgeEffectView extends View {
        public EdgeEffect edgeEffect;

        public CustomEdgeEffectView(Context context) {
            this(context, null);
        }
        public CustomEdgeEffectView(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public CustomEdgeEffectView(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public CustomEdgeEffectView(
                Context context,
                AttributeSet attrs,
                int defStyleAttr,
                int defStyleRes
        ) {
            super(context, attrs, defStyleAttr, defStyleRes);
            edgeEffect = new EdgeEffect(context, attrs);
        }
    }
}
