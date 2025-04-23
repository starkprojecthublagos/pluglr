// audioQueueManager.js
export class AudioQueueManager {
    constructor(pitchFactor = 1.0) {
        this.audioQueue = [];
        this.isPlaying = false;
        this.pitchFactor = pitchFactor;
        this.audioContext = new AudioContext({ sampleRate: 16000 });
    }

    addAudioToQueue(audioData) {
        this.audioQueue.push(audioData);
        this.playNext();
    }

    async playNext() {
        if (this.isPlaying || this.audioQueue.length === 0) return;

        this.isPlaying = true;
        const buffer = this.audioQueue.shift();
        await this.playAudio(buffer);
        this.isPlaying = false;
        this.playNext();
    }

    playAudio(int16Array) {
        return new Promise((resolve) => {
            const float32Array = new Float32Array(int16Array.length);
            for (let i = 0; i < int16Array.length; i++) {
                float32Array[i] = int16Array[i] / 0x7FFF;
            }

            const audioBuffer = this.audioContext.createBuffer(1, float32Array.length, this.audioContext.sampleRate);
            audioBuffer.copyToChannel(float32Array, 0);

            const source = this.audioContext.createBufferSource();
            source.buffer = audioBuffer;
            source.playbackRate.value = this.pitchFactor;
            source.connect(this.audioContext.destination);

            source.onended = resolve;
            source.start();
        });
    }
}

export const audioQueueManager = new AudioQueueManager();
