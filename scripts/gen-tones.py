#!/usr/bin/env python3
"""Generate pure-tone WAV files for checking pitch detection on a real phone.

Play them from a computer speaker next to the phone while the app is on the
Practice screen (use 「等正確」 mode so the cursor stays put), then read the
「你拉」 value with `scripts/phone.sh text practice_detected`.

    python3 scripts/gen-tones.py [out_dir]          # default: ./build/tones
    afplay build/tones/A5_880.wav                   # macOS

The default set spans the cello range and includes the notes above ~830 Hz that
the detector used to report an octave low (docs/arc42/08_crosscutting_concepts.md §8.2).
"""
import math
import os
import struct
import sys
import wave

SR = 44100
SECONDS = 4.0
TONES = [
    ("C2_65.41", 65.41), ("G2_98", 98.0), ("A3_220", 220.0), ("A4_440", 440.0),
    ("A5_880", 880.0), ("B5_987.77", 987.77), ("C6_1046.5", 1046.5),
]


def write_tone(path: str, freq: float) -> None:
    n = int(SR * SECONDS)
    fade = 2000  # ~45 ms fade in/out, avoids clicks
    frames = bytearray()
    for i in range(n):
        env = min(1.0, i / fade, (n - i) / fade)
        frames += struct.pack("<h", int(0.6 * env * 32767 * math.sin(2 * math.pi * freq * i / SR)))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(bytes(frames))


def main() -> None:
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join("build", "tones")
    os.makedirs(out, exist_ok=True)
    for name, freq in TONES:
        path = os.path.join(out, f"{name}.wav")
        write_tone(path, freq)
        print(path)


if __name__ == "__main__":
    main()
