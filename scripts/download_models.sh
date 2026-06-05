#!/bin/bash
# Cipher additional models download
MODEL_DIR="/data/local/tmp/cipher_models"
mkdir -p $MODEL_DIR

echo "Downloading VibeThinker-1.5B (abliterated coding model)..."
curl -L -o $MODEL_DIR/vibethinker-1.5b.litertlm \
  "https://huggingface.co/litert-community/VibeThinker-1.5B/resolve/main/vibethinker-1.5b-it-int4.litertlm"

echo "Downloading Gemma 4 E2B vision model..."
curl -L -o $MODEL_DIR/gemma-4-E2B-vision.litertlm \
  "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-int4.litertlm"

echo "Done:"
ls -lh $MODEL_DIR/
