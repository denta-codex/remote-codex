package com.squareup.seismic;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShakeDetectorTest {
  @Test public void deliberateMotionTriggersButNormalMotionAndAnImpactDoNot() {
    ShakeDetector.SampleQueue queue = new ShakeDetector.SampleQueue();
    for (int i = 0; i < 20; i++) queue.add(i * 20000000L, false);
    assertFalse(queue.isShaking());
    queue.add(400000000L, true);
    assertFalse(queue.isShaking());
    queue.clear();
    for (int i = 0; i < 20; i++) queue.add(i * 20000000L, true);
    assertTrue(queue.isShaking());
    queue.clear();
    assertFalse(queue.isShaking());
    queue.add(1000000000L, true);
    assertFalse(queue.isShaking());
  }
}
