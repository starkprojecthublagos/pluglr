export async function startRealtimeListening(socket) {
    try {
        const stream = await navigator.mediaDevices.getUserMedia({
            audio: {
                sampleRate: 16000,
                channelCount: 1,
                echoCancellation: true,
            },
        });

        const audioContext = new AudioContext({ sampleRate: 16000 });
        const source = audioContext.createMediaStreamSource(stream);
        const processor = audioContext.createScriptProcessor(4096, 1, 1);

        processor.onaudioprocess = (event) => {
            const audioData = event.inputBuffer.getChannelData(0); // Float32Array
            const int16Buffer = convertFloat32ToInt16(audioData);  // Convert to Int16

            if (socket.readyState === WebSocket.OPEN) {
                socket.send(int16Buffer); // Send as binary
            }
        };

        source.connect(processor);
        processor.connect(audioContext.destination); // You can mute this if you don't want to hear your mic

        console.log("Started real-time mic streaming");
    } catch (error) {
        console.error("Mic access error:", error);
    }
}

export function convertFloat32ToInt16(buffer) {
    const l = buffer.length;
    const result = new Int16Array(l);
    for (let i = 0; i < l; i++) {
        result[i] = buffer[i] * 0x7FFF; // convert to 16-bit PCM
    }
    return result.buffer; // Return ArrayBuffer
}
