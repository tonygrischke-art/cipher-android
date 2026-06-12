#!/bin/bash
# fix_vance_core.sh
# Run in Termux: bash fix_vance_core.sh
# Fixes VanceCoreService NpuEngine Hilt crash + wires service start

REPO="$HOME/cipher-android"
cd "$REPO" || { echo "ERROR: cipher-android not found"; exit 1; }

echo "=== Vance Core Fix Script ==="

# Find VanceCoreService.kt
SVC=$(find . -name "VanceCoreService.kt" | head -1)
CHAT=$(find . -name "VanceChatActivity.kt" | head -1)

echo "Found: $SVC"
echo "Found: $CHAT"

if [ -z "$SVC" ]; then
  echo "ERROR: VanceCoreService.kt not found"
  exit 1
fi

# Backup originals
cp "$SVC" "${SVC}.bak"
cp "$CHAT" "${CHAT}.bak" 2>/dev/null

# ---- FIX 1: Remove @Inject from NpuEngine in VanceCoreService ----
echo "Fixing NpuEngine injection..."

# Remove @Inject annotation before npuEngine declaration
sed -i '/@Inject/{
  N
  s/@Inject\n\s*\(.*NpuEngine.*\)/\/\/ NpuEngine injected manually — see onCreate()/
}' "$SVC"

# Also handle same-line @Inject
sed -i 's/@Inject.*NpuEngine/\/\/ NpuEngine — manual init/g' "$SVC"

# Remove lateinit var npuEngine if declared at class level with @Inject
sed -i '/^.*@Inject.*$/d' "$SVC"

# ---- FIX 2: Add manual NpuEngine init in onCreate ----
echo "Adding manual NpuEngine init to onCreate..."

# Check if npuEngine is already declared
if ! grep -q "lateinit var npuEngine" "$SVC" && ! grep -q "private var npuEngine" "$SVC"; then
  # Add field declaration after class opening line
  sed -i '/^class VanceCoreService/a\    private var npuEngine: NpuEngine? = null' "$SVC"
fi

# Add init block inside onCreate after super.onCreate()
# Using python for reliable multi-line insertion
python3 - "$SVC" << 'PYEOF'
import sys
path = sys.argv[1]
with open(path, 'r') as f:
    content = f.read()

init_block = '''
        // NpuEngine manual init — replaces Hilt injection
        Log.i("VanceCoreService", "onCreate: starting NpuEngine init")
        try {
            npuEngine = NpuEngine(applicationContext)
            Log.i("VanceCoreService", "NpuEngine created successfully")
        } catch (e: Exception) {
            Log.e("VanceCoreService", "NpuEngine init failed: ${e.message}")
        }
'''

# Insert after super.onCreate()
if 'super.onCreate()' in content and 'NpuEngine manual init' not in content:
    content = content.replace(
        'super.onCreate()',
        'super.onCreate()' + init_block,
        1  # only first occurrence
    )
    with open(path, 'w') as f:
        f.write(content)
    print("✓ NpuEngine manual init injected into onCreate()")
else:
    print("⚠ super.onCreate() not found or already patched — check manually")
PYEOF

# ---- FIX 3: Wire startForegroundService from VanceChatActivity ----
if [ -n "$CHAT" ]; then
  echo "Wiring startForegroundService in VanceChatActivity..."

  python3 - "$CHAT" << 'PYEOF'
import sys
path = sys.argv[1]
with open(path, 'r') as f:
    content = f.read()

service_start = '''
        // Start VanceCoreService
        try {
            startForegroundService(
                android.content.Intent(this, com.aetheria.vance.core.VanceCoreService::class.java)
            )
            android.util.Log.i("VanceChatActivity", "VanceCoreService start requested")
        } catch (e: Exception) {
            android.util.Log.e("VanceChatActivity", "Failed to start VanceCoreService: ${e.message}")
        }
'''

if 'VanceCoreService start requested' not in content:
    if 'super.onCreate' in content:
        content = content.replace(
            'super.onCreate(savedInstanceState)',
            'super.onCreate(savedInstanceState)' + service_start,
            1
        )
        with open(path, 'w') as f:
            f.write(content)
        print("✓ startForegroundService wired into VanceChatActivity.onCreate()")
    else:
        print("⚠ super.onCreate not found in VanceChatActivity — check manually")
else:
    print("✓ Already wired — skipping")
PYEOF
fi

# ---- FIX 4: Add Log import if missing ----
echo "Checking Log import..."
if ! grep -q "import android.util.Log" "$SVC"; then
  sed -i '1s/^/import android.util.Log\n/' "$SVC"
  echo "✓ Added Log import"
fi

# ---- VERIFY ----
echo ""
echo "=== Verification ==="
echo "VanceCoreService onCreate section:"
grep -A 20 "override fun onCreate" "$SVC" | head -25

echo ""
echo "VanceChatActivity onCreate section:"
grep -A 15 "override fun onCreate" "$CHAT" | head -20

# ---- COMMIT AND PUSH ----
echo ""
echo "=== Pushing to GitHub ==="
git add -A
git commit -m "fix: manual NpuEngine init in VanceCoreService + wire startForegroundService

- Removed NpuEngine from Hilt @Inject (was crashing service before first log)
- Added manual instantiation in VanceCoreService.onCreate() with try/catch
- Added startForegroundService call from VanceChatActivity.onCreate()
- Both init steps now log success/failure for logcat verification"

git push origin main

echo ""
echo "=== Done ==="
echo "Check CI: gh run list --limit 1"
echo "After install, run: rish -c \"logcat -d | grep -iE 'VanceCoreService|NpuEngine|NeuronBridge'\""
