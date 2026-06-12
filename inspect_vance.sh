#!/bin/bash
# fix_vance_v2.sh
# Run: bash fix_vance_v2.sh
# Uses only sed/awk — no Python string mangling

REPO="$HOME/cipher-android"
cd "$REPO" || { echo "ERROR: repo not found"; exit 1; }

SVC=$(find . -path "*/core/VanceCoreService.kt" | head -1)
CHAT=$(find . -path "*/ui/VanceChatActivity.kt" | head -1)

echo "Service: $SVC"
echo "Activity: $CHAT"

[ -z "$SVC" ] && { echo "ERROR: VanceCoreService.kt not found"; exit 1; }

# Show current state
echo ""
echo "=== Current VanceCoreService.kt (first 40 lines) ==="
head -40 "$SVC"

echo ""
echo "=== Current NpuEngine references ==="
grep -n "NpuEngine\|@Inject\|super.onCreate" "$SVC"

echo ""
echo "=== Current VanceChatActivity onCreate ==="
grep -n "onCreate\|VanceCoreService\|startForeground" "$CHAT"

echo ""
echo "Paste output to Claude for targeted fix."
