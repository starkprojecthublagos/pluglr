class AudioSenderProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.floatBuffer = [];
        this.port.onmessage = (e) => {
            if (e.data === 'clear') this.floatBuffer = [];
        };
    }

    process(inputs) {
        const input = inputs[0];
        const samples = input[0];

        if (!samples) return true;

        // Push new samples
        this.floatBuffer.push(...samples);

        // While enough for one frame
        while (this.floatBuffer.length >= 960) {
            let frame = this.floatBuffer.slice(0, 960);
            this.floatBuffer = this.floatBuffer.slice(960);

            // Silence detection (very basic)
            let isSilent = frame.every(sample => Math.abs(sample) < 0.01);
            if (isSilent) continue;

            // Optional normalization
            let max = Math.max(...frame.map(Math.abs));
            if (max > 0.9) {
                frame = frame.map(s => s * 0.9 / max);
            }

            // Convert to Int16
            const int16 = new Int16Array(960);
            for (let i = 0; i < 960; i++) {
                const s = Math.max(-1, Math.min(1, frame[i]));
                int16[i] = s * 32767;
            }

            // Send to main thread
            this.port.postMessage(int16.buffer, [int16.buffer]);
        }

        return true;
    }
}

registerProcessor('audio-sender-processor', AudioSenderProcessor);
