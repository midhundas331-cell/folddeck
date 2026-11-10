#!/usr/bin/env python3
"""Fetch the word lists the smart keyboard uses and trim them for the APK.

Not in git: the lists are third-party data (SymSpell's English frequency
dictionaries, via symspellpy, MIT-licensed repo). Run once before build.sh;
without them the app still builds and the smart keyboard simply stays quiet.

  assets/words.txt  "word count"       the WORDS most common words
  assets/next.txt   "word a b c"       each word's three likeliest followers
"""
import os, sys, urllib.request
from collections import defaultdict

BASE = "https://raw.githubusercontent.com/mammothb/symspellpy/master/symspellpy/"
UNIGRAMS = "frequency_dictionary_en_82_765.txt"
BIGRAMS = "frequency_bigramdictionary_en_243_342.txt"
WORDS = 30000
HERE = os.path.dirname(os.path.abspath(__file__))


def lines(name, cache):
    path = os.path.join(cache, name) if cache else None
    if path and os.path.exists(path):
        return open(path, encoding="utf-8").read().splitlines()
    with urllib.request.urlopen(BASE + name, timeout=60) as r:
        return r.read().decode("utf-8").splitlines()


def main():
    cache = sys.argv[1] if len(sys.argv) > 1 else None   # a folder of already-downloaded files
    words = {}
    for line in lines(UNIGRAMS, cache):
        parts = line.split()
        if len(parts) == 2 and parts[0].isascii() and parts[0].isalpha() and parts[0].islower():
            words[parts[0]] = int(parts[1])
    top = sorted(words, key=words.get, reverse=True)[:WORDS]
    keep = set(top)
    follow = defaultdict(list)
    for line in lines(BIGRAMS, cache):
        parts = line.split()
        if len(parts) == 3 and parts[0] in keep and parts[1] in keep:
            follow[parts[0]].append((int(parts[2]), parts[1]))
    os.makedirs(os.path.join(HERE, "assets"), exist_ok=True)
    with open(os.path.join(HERE, "assets", "words.txt"), "w") as f:
        for w in top:
            f.write(f"{w} {max(1, words[w] // 1000)}\n")   # scaled to fit an int
    with open(os.path.join(HERE, "assets", "next.txt"), "w") as f:
        for w in sorted(follow):
            best = [b for _, b in sorted(follow[w], reverse=True)[:3]]
            f.write(w + " " + " ".join(best) + "\n")
    print(f"words.txt: {len(top)} words, next.txt: {len(follow)} entries")


if __name__ == "__main__":
    main()
