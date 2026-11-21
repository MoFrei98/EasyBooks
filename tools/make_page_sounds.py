"""
Erzeugt die Umblätter-Geräusche für Easy Books synthetisch (keine fremden Aufnahmen,
daher keine Lizenzfragen).

Aufruf:  python tools/make_page_sounds.py
Ausgabe: app/src/main/res/raw/page_turn_1.wav … page_turn_3.wav, page_slide.wav, cover_open.wav

Ein Papier-Rascheln besteht hier aus zwei Teilen:
- "Swish": gefiltertes Rauschen, das an- und abschwillt (die Seite streicht durch die Luft)
- "Knistern": viele kurze, helle Klicks (das Papier biegt sich)

Bewusst ohne dumpfen Ton am Ende: Der klang wie ein zugeschlagenes Hardcover-Buch.

Genau den gibt es dafür beim Buchdeckel (cover_open.wav, Ansicht "Altes Buch"):
Der Deckel streicht leise durch die Luft und liegt dumpf auf. (Ein Knarzen im
Falz vorweg klang eher nach etwas anderem und ist wieder raus.)
"""
import os
import wave

import numpy as np

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def bandpass(signal, low, high):
    """Bandpass über die FFT mit weichen Flanken."""
    spectrum = np.fft.rfft(signal)
    freqs = np.fft.rfftfreq(len(signal), 1 / RATE)
    rise = np.clip((freqs - low * 0.7) / (low * 0.3), 0, 1)
    fall = np.clip((high * 1.3 - freqs) / (high * 0.3), 0, 1)
    return np.fft.irfft(spectrum * rise * fall, n=len(signal))


def envelope(n, attack, peak, release):
    """Hüllkurve: steigt bis `peak` (Anteil der Länge) an, fällt dann ab."""
    t = np.linspace(0, 1, n)
    up = np.clip(t / peak, 0, 1) ** attack
    down = np.clip((1 - t) / (1 - peak), 0, 1) ** release
    return up * down


def page_turn(seed, length=0.32, brightness=1.0, crackle=1.0):
    rng = np.random.default_rng(seed)
    n = int(RATE * length)
    t = np.arange(n) / RATE

    # Swish: Rauschen mit langsam schwankender Lautstärke
    swish = bandpass(rng.standard_normal(n), 900, 6500 * brightness)
    wobble = 1 + 0.35 * np.interp(t, np.linspace(0, length, 9), rng.uniform(-1, 1, 9))
    # Früher Höhepunkt, dann langes, weiches Ausklingen.
    swish *= envelope(n, 1.4, rng.uniform(0.25, 0.35), 2.2) * wobble

    # Knistern: kurze Klicks, gehäuft in der Mitte
    clicks = np.zeros(n)
    density = envelope(n, 1.0, 0.3, 2.0)
    for i in np.nonzero(rng.random(n) < 0.012 * density)[0]:
        length_click = rng.integers(20, 90)
        end = min(n, i + length_click)
        decay = np.exp(-np.arange(end - i) / rng.uniform(6, 18))
        clicks[i:end] += rng.uniform(0.3, 1.0) * rng.choice([-1, 1]) * decay
    clicks = bandpass(clicks, 2500, 9000 * brightness) * crackle

    mix = 0.9 * swish / (np.abs(swish).max() + 1e-9) \
        + 0.55 * clicks / (np.abs(clicks).max() + 1e-9)
    return mix


def hardcover(seed, length=1.05):
    """Ein fester Buchdeckel wird aufgeklappt und legt sich ab."""
    rng = np.random.default_rng(seed)
    n = int(RATE * length)
    t = np.arange(n) / RATE

    # Luftzug: tiefes, weiches Rauschen, das anschwillt, solange der Deckel schwingt.
    whoosh = bandpass(rng.standard_normal(n), 180, 1600)
    whoosh *= envelope(n, 1.6, 0.55, 3.0)

    # Aufliegen: tiefer, kurzer Ton mit fallender Tonhöhe und etwas Klopfen.
    land = int(RATE * 0.74)
    k = np.arange(n - land) / RATE
    freq = 95 * np.exp(-k * 3.0) + 55
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    thud = np.zeros(n)
    thud[land:] = np.sin(phase) * np.exp(-k / 0.075)
    knock = np.zeros(n)
    knock[land:] = bandpass(rng.standard_normal(n - land), 120, 900) * np.exp(-k / 0.03)

    mix = 0.3 * whoosh / (np.abs(whoosh).max() + 1e-9) \
        + 1.0 * thud \
        + 0.45 * knock / (np.abs(knock).max() + 1e-9)
    return mix


def finish(signal, peak_db):
    fade_in = int(RATE * 0.006)
    fade_out = int(RATE * 0.06)
    signal[:fade_in] *= np.linspace(0, 1, fade_in)
    signal[-fade_out:] *= np.linspace(1, 0, fade_out) ** 2
    signal = signal / np.abs(signal).max() * 10 ** (peak_db / 20)
    return (signal * 32767).astype(np.int16)


def save(name, samples):
    path = os.path.normpath(os.path.join(OUT, name))
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(samples.tobytes())
    print(f"{path}  {len(samples) / RATE:.2f} s")


os.makedirs(OUT, exist_ok=True)
save("page_turn_1.wav", finish(page_turn(seed=11), -4))
save("page_turn_2.wav", finish(page_turn(seed=23, length=0.35, brightness=0.9), -4))
save("page_turn_3.wav", finish(page_turn(seed=37, length=0.3, brightness=1.1, crackle=1.2), -4))
# Slideshow: kürzer, weicher, leiser, ohne Knistern
save("page_slide.wav", finish(page_turn(seed=5, length=0.24, brightness=0.7, crackle=0.0), -12))
# Buchdeckel (Ansicht "Altes Buch", erste Seite)
save("cover_open.wav", finish(hardcover(seed=41), -3))
