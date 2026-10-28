"""Wake word check for Vosk: synthesises Korean phrases with edge-tts (several voices) and measures
how often the wake word is detected (should be ~100%) and falsely triggered (should be ~0%).

  py -3.13 wakeword_test.py                      # needs: pip install vosk edge-tts, ffmpeg on PATH
  py -3.13 wakeword_test.py --phrase 싱크로야 --word 싱크로
  py -3.13 wakeword_test.py --phrase 씽크 --tokens 싱크,신키

Same free-transcription matching as the Android WakeWordListener, so results carry over.
"""

import argparse
import asyncio
import json
import subprocess
import tempfile
import wave
from pathlib import Path

import edge_tts
import vosk

VOICES = ["ko-KR-SunHiNeural", "ko-KR-InJoonNeural", "ko-KR-HyunsuMultilingualNeural"]
NEGATIVE = [
    "싱싱한 과일 주세요", "신신당부했잖아", "씽씽 달려라", "씽크대 청소해야지", "오늘 날씨 어때?", "싱크로 노래방",
    "씩씩하게 걸어가자", "찡찡거리지 마", "쌩쌩 달린다", "신발 신어", "심심해", "띵동",
    "아이유 노래 찾아줘", "볼륨 좀 키워줘", "그래서 그게 뭔데", "안녕하세요 반갑습니다",
]


async def synth(text: str, voice: str, out_wav: Path):
    mp3 = out_wav.with_suffix(".mp3")
    await edge_tts.Communicate(text, voice).save(str(mp3))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(mp3), "-ar", "16000", "-ac", "1", str(out_wav)], check=True)


def transcribe(model: vosk.Model, wav_path: Path) -> str:
    """Free (no grammar) transcription: this Korean model has no [unk] word, so a grammar of just the
    wake word forces every sound onto it. Instead, transcribe and look for the wake word in the text."""
    rec = vosk.KaldiRecognizer(model, 16000)
    with wave.open(str(wav_path), "rb") as wf:
        # trailing silence so the final utterance is flushed like live audio
        frames = wf.readframes(wf.getnframes()) + b"\0" * 16000
    texts = []
    for i in range(0, len(frames), 4000):
        if rec.AcceptWaveform(frames[i : i + 4000]):
            texts.append(json.loads(rec.Result())["text"])
    texts.append(json.loads(rec.FinalResult())["text"])
    return " ".join(t for t in texts if t)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(Path(__file__).resolve().parents[2] / "models" / "vosk-model-small-ko-0.22"))
    ap.add_argument("--phrase", default="싱크로야", help="wake phrase as spoken")
    ap.add_argument("--word", default="싱크로", help="what must appear in the transcript (substring)")
    ap.add_argument("--tokens", help="comma-separated: wake only if one of these is a whole word in the transcript")
    args = ap.parse_args()
    positive = [args.phrase, f"{args.phrase}, 오늘 날씨 어때?", f"저기 {args.phrase}", f"{args.phrase} 노래 틀어줘", f"{args.phrase} 볼륨 좀 줄여줘"]

    vosk.SetLogLevel(-1)
    model = vosk.Model(args.model)
    rows = []
    with tempfile.TemporaryDirectory() as tmp:
        for label, phrases in (("POS", positive), ("NEG", NEGATIVE)):
            for text in phrases:
                for voice in VOICES:
                    wav = Path(tmp) / f"{len(rows)}.wav"
                    await synth(text, voice, wav)
                    rows.append((label, text, voice, transcribe(model, wav)))

    tokens = set(args.tokens.split(",")) if args.tokens else None

    def wakes(heard: str) -> bool:
        return bool(tokens & set(heard.split())) if tokens else args.word in heard.replace(" ", "")

    for label, text, voice, heard in rows:
        print(f"{label} {'WAKE' if wakes(heard) else '----'}  {voice.split('-')[2]:<26} {text}  ->  {heard}")
    pos = [wakes(h) for lbl, *_, h in rows if lbl == "POS"]
    neg = [wakes(h) for lbl, *_, h in rows if lbl == "NEG"]
    print(f"detected {sum(pos)}/{len(pos)}, false wake {sum(neg)}/{len(neg)}")


if __name__ == "__main__":
    asyncio.run(main())
