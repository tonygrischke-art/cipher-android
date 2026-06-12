#!/bin/bash
# fix_vance_v3.sh — clean sed-only fix, no Python
cd ~/cipher-android || exit 1

SVC="app/src/main/java/com/aetheria/vance/core/VanceCoreService.kt"
CHAT="app/src/main/java/com/aetheria/vance/ui/VanceChatActivity.kt"

echo "=== Fix 1: Remove @Inject from npuEngine and tfliteLlmEngine ==="

# Remove @Inject from npuEngine line (line 61)
sed -i 's/    @Inject lateinit var npuEngine: NpuEngine/    private lateinit var npuEngine: NpuEngine/' "$SVC"

# Remove @Inject from tfliteLlmEngine line (line 62)
sed -i 's/    @Inject lateinit var tfliteLlmEngine: TfliteLlmEngine/    private lateinit var tfliteLlmEngine: TfliteLlmEngine/' "$SVC"

echo "=== Fix 2: Add manual init after super.onCreate() ==="

# Use awk to insert init block after super.onCreate()
# Using awk avoids all shell escaping issues with ${}
awk '
/super\.onCreate\(\)/ && !done {
    print $0
    print "        Log.i(\"VanceCoreService\", \"onCreate started\")"
    print "        try {"
    print "            npuEngine = NpuEngine(applicationContext)"
    print "            Log.i(\"VanceCoreService\", \"NpuEngine created OK\")"
    print "        } catch (e: Exception) {"
    print "            Log.e(\"VanceCoreService\", \"NpuEngine failed: \" + e.message)"
    print "        }"
    print "        try {"
    print "            tfliteLlmEngine = TfliteLlmEngine(applicationContext)"
    print "            tfliteLlmEngine.initialize(\"/data/local/tmp/cipher_models/qwen05_clean.task\")"
    print "            Log.i(\"VanceCoreService\", \"TfliteLlmEngine initialized OK\")"
    print "        } catch (e: Exception) {"
    print "            Log.e(\"VanceCoreService\", \"TfliteLlmEngine failed: \" + e.message)"
    print "        }"
    done=1
    next
}
{ print }
' "$SVC" > /tmp/vcs_fixed.kt && mv /tmp/vcs_fixed.kt "$SVC"

echo "=== Fix 3: Verify VanceChatActivity starts the service ==="

# Check if service is already started from onCreate
if grep -q "startForegroundService" "$CHAT"; then
    echo "startForegroundService already present — skipping"
else
    # Add after super.onCreate(savedInstanceState)
    awk '
/super\.onCreate\(savedInstanceState\)/ && !done {
    print $0
    print "        try {"
    print "            startForegroundService(android.content.Intent(this, VanceCoreService::class.java))"
    print "            Log.i(\"VanceChatActivity\", \"VanceCoreService started\")"
    print "        } catch (e: Exception) {"
    print "            Log.e(\"VanceChatActivity\", \"Service start failed: \" + e.message)"
    print "        }"
    done=1
    next
}
{ print }
' "$CHAT" > /tmp/chat_fixed.kt && mv /tmp/chat_fixed.kt "$CHAT"
    echo "startForegroundService added"
fi

echo ""
echo "=== Verification ==="
echo "--- VanceCoreService NpuEngine lines ---"
grep -n "npuEngine\|tfliteEngine\|super.onCreate\|NpuEngine created\|NpuEngine failed" "$SVC" | head -20

echo ""
echo "--- VanceChatActivity service start ---"
grep -n "startForegroundService\|VanceCoreService started" "$CHAT" | head -5

echo ""
echo "=== Pushing to GitHub ==="
git add -A
git commit -m "fix: manual NpuEngine+TfliteLlmEngine init, remove from Hilt injection"
git push origin master

echo ""
echo "=== Done — check CI: gh run list --limit 1 ==="
