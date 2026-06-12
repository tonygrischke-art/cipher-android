#!/bin/bash
# fix_inject.sh — restore @Inject to npuEngine and tfliteLlmEngine
cd ~/cipher-android || exit 1

SVC="app/src/main/java/com/aetheria/vance/core/VanceCoreService.kt"
CHAT="app/src/main/java/com/aetheria/vance/ui/VanceChatActivity.kt"

echo "Before fix:"
grep -n "npuEngine\|tfliteLlmEngine" "$SVC" | grep "lateinit"

# Restore @Inject on both engine fields
sed -i 's/    private lateinit var npuEngine: NpuEngine/    @Inject lateinit var npuEngine: NpuEngine/' "$SVC"
sed -i 's/    private lateinit var tfliteLlmEngine: TfliteLlmEngine/    @Inject lateinit var tfliteLlmEngine: TfliteLlmEngine/' "$SVC"

echo "After fix:"
grep -n "npuEngine\|tfliteLlmEngine" "$SVC" | grep "lateinit"

# Also ensure VanceChatActivity starts the service from onCreate
if ! grep -q "startForegroundService" "$CHAT"; then
    awk '
/super\.onCreate\(savedInstanceState\)/ && !done {
    print $0
    print "        try { startForegroundService(android.content.Intent(this, com.aetheria.vance.core.VanceCoreService::class.java)) } catch (e: Exception) { android.util.Log.e(\"VanceChatActivity\", \"svc start: \" + e.message) }"
    done=1
    next
}
{ print }
' "$CHAT" > /tmp/chat.kt && mv /tmp/chat.kt "$CHAT"
    echo "VanceChatActivity: startForegroundService added"
else
    echo "VanceChatActivity: already starts service"
fi

git add -A
git commit -m "fix: restore @Inject on npuEngine+tfliteLlmEngine, wire service start"
git push origin master

echo "Done. Check: gh run list --limit 1"
