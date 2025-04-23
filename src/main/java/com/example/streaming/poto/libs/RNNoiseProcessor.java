package com.example.streaming.poto.libs;

import com.sun.jna.Library;
import com.sun.jna.Native;

public class RNNoiseProcessor {
    public interface RNNoise extends Library {
        RNNoise INSTANCE = Native.load("rnnoise", RNNoise.class);

        void rnnoise_process_frame(float[] out, float[] in);
    }

    public static byte[] processAudioFrame(byte[] pcmAudio) {
        // RNNoise expects 480 samples (960 bytes)
        if (pcmAudio == null || pcmAudio.length != 960) {
            System.out.println("❌ RNNoise expects 960 bytes. Got: " + (pcmAudio != null ? pcmAudio.length : "null"));
            return pcmAudio; // fallback: skip processing
        }

        try {
            float[] floatSamples = convertBytesToFloats(pcmAudio);
            float[] output = new float[floatSamples.length];

            System.out.println("🟡 RNNoise processing started");
            RNNoise.INSTANCE.rnnoise_process_frame(output, floatSamples);
            System.out.println("✅ RNNoise processing done");

            return convertFloatsToBytes(output);
        } catch (Exception e) {
            System.out.println("💥 RNNoise processing failed: " + e.getMessage());
            return pcmAudio; // fallback
        }
    }

    private static float[] convertBytesToFloats(byte[] bytes) {
        float[] floats = new float[bytes.length / 2];
        for (int i = 0; i < floats.length; i++) {
            short sample = (short) ((bytes[2 * i + 1] << 8) | (bytes[2 * i] & 0xFF));
            floats[i] = sample / 32768.0f; // Normalize to [-1, 1]
        }
        return floats;
    }

    private static byte[] convertFloatsToBytes(float[] floats) {
        byte[] bytes = new byte[floats.length * 2];
        for (int i = 0; i < floats.length; i++) {
            short sample = (short) (floats[i] * 32767);
            bytes[2 * i] = (byte) (sample & 0xFF);
            bytes[2 * i + 1] = (byte) (sample >> 8);
        }
        return bytes;
    }
}
