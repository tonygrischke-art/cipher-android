#!/usr/bin/env python3
"""Fix critical lint issues in Cipher codebase."""
import re
import os

BASE = os.path.expanduser("~/cipher-android/app/src/main/java/com/aetheria/vance")

def read_file(path):
    with open(path, 'r') as f:
        return f.read()

def write_file(path, content):
    with open(path, 'w') as f:
        f.write(content)
    print(f"  Wrote: {path}")

def remove_version_check_block(content, pattern, keep_inner=True):
    """Remove an if/else version check block, keeping only the 'if' branch content."""
    # Match: if (Build.VERSION.SDK_INT >= X) { ... } else { ... }
    # We need to handle nested braces
    match = re.search(pattern, content, re.DOTALL)
    if not match:
        return content, False
    
    start = match.start()
    # Find the opening brace after the if condition
    brace_start = content.index('{', match.end())
    
    # Count braces to find the matching closing brace
    depth = 1
    pos = brace_start + 1
    while depth > 0 and pos < len(content):
        if content[pos] == '{':
            depth += 1
        elif content[pos] == '}':
            depth -= 1
        pos += 1
    
    # pos is now past the closing brace of the if block
    if_block_end = pos
    
    # Check for else
    rest = content[if_block_end:].lstrip()
    if rest.startswith('else'):
        # Find the else block
        else_brace = rest.index('{')
        depth = 1
        epos = else_brace + 1
        while depth > 0 and epos < len(rest):
            if rest[epos] == '{':
                depth += 1
            elif rest[epos] == '}':
                depth -= 1
            epos += 1
        else_block_end = if_block_end + epos
    else:
        else_block_end = if_block_end
    
    # Extract the inner content of the if block (between braces)
    if_inner = content[brace_start+1:if_block_end-1]
    
    # Replace the entire if/else block with just the inner content
    new_content = content[:start] + if_inner + content[else_block_end:]
    return new_content, True

# ── 1. FIX HARDCODED PATHS ────────────────────────────────────────────

print("=== 1. Hardcoded Paths ===")

# ShizukuBridge.kt — two rishPath lists with hardcoded "/data/" paths
path = f"{BASE}/shizuku/ShizukuBridge.kt"
content = read_file(path)

# Replace the two hardcoded rishPath lists
# Pattern: listOf(\n    "/data/local/tmp/rish",\n    "/data/user/0/moe.shizuku.privileged.api/rish",\n    "rish"
old_rish = '''listOf(
                        "/data/local/tmp/rish",
                        "/data/user/0/moe.shizuku.privileged.api/rish",
                        "rish"  // fallback to PATH
                    ).firstOrNull { it == "rish" || java.io.File(it).exists() } ?: "rish"'''
new_rish = '''listOf(
                        "${context.filesDir.parent}/bin/rish",
                        "/data/local/tmp/rish",
                        "rish"  // fallback to PATH
                    ).firstOrNull { it == "rish" || java.io.File(it).exists() } ?: "rish"'''

count = content.count(old_rish)
print(f"  ShizukuBridge.kt: found {count} rishPath occurrences")
content = content.replace(old_rish, new_rish)
write_file(path, content)

# ActionExecutor.kt — line 395: hardcoded "/sdcard/" default
path = f"{BASE}/actions/ActionExecutor.kt"
content = read_file(path)
old = 'val outputPath = params.optString("output_path", "/sdcard/cipher_screenshot.png")'
new = 'val outputPath = params.optString("output_path", "${android.os.Environment.getExternalStorageDirectory().absolutePath}/cipher_screenshot.png")'
if old in content:
    content = content.replace(old, new)
    print(f"  ActionExecutor.kt: replaced /sdcard/ default")
    write_file(path, content)
else:
    print(f"  ActionExecutor.kt: pattern not found, checking...")
    # Try to find the line
    for i, line in enumerate(content.split('\n'), 1):
        if '/sdcard/' in line:
            print(f"    Line {i}: {line.strip()}")

# VanceAccessibilityService.kt — line 248: hardcoded "/sdcard/"
path = f"{BASE}/accessibility/VanceAccessibilityService.kt"
content = read_file(path)
old = 'val outputPath = "/sdcard/cipher_screenshot.png"'
new = 'val outputPath = "${android.os.Environment.getExternalStorageDirectory().absolutePath}/cipher_screenshot.png"'
if old in content:
    content = content.replace(old, new)
    print(f"  VanceAccessibilityService.kt: replaced /sdcard/")
    write_file(path, content)
else:
    print(f"  VanceAccessibilityService.kt: pattern not found, checking...")
    for i, line in enumerate(content.split('\n'), 1):
        if '/sdcard/' in line:
            print(f"    Line {i}: {line.strip()}")

# ── 2. STRIP DEAD SDK_INT CHECKS ──────────────────────────────────────

print("\n=== 2. Dead SDK_INT Checks ===")

# Helper: remove if/else version check, keep only the 'if' branch
def strip_version_check(filepath, description, search_pattern, keep_branch='if'):
    path = f"{BASE}/{filepath}"
    content = read_file(path)
    
    # Find the pattern
    match = re.search(search_pattern, content, re.DOTALL)
    if not match:
        print(f"  {description}: pattern not found")
        return
    
    # Get position and find braces
    start = match.start()
    
    # Find opening brace
    brace_open = content.index('{', match.end())
    
    # Count braces
    depth = 1
    pos = brace_open + 1
    while depth > 0:
        if content[pos] == '{': depth += 1
        elif content[pos] == '}': depth -= 1
        pos += 1
    
    if_block_content = content[brace_open+1:pos-1]
    
    # Check for else
    rest = content[pos:].lstrip()
    if rest.startswith('else'):
        # Skip else block
        else_brace = rest.index('{')
        depth = 1
        epos = else_brace + 1
        while depth > 0:
            if rest[epos] == '{': depth += 1
            elif rest[epos] == '}': depth -= 1
            epos += 1
        end = pos + epos
    else:
        end = pos
    
    # Replace with just the if-branch content
    new_content = content[:start] + if_block_content + content[end:]
    write_file(path, new_content)
    print(f"  {description}: stripped")

# 2a. VanceApplication.kt:57 — startForegroundService
strip_version_check(
    "VanceApplication.kt",
    "VanceApplication.kt startForegroundService",
    r'if \(Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.O\) \{'
)

# 2b. VanceCoreService.kt:203 — startForegroundService for FloatingOrb
strip_version_check(
    "core/VanceCoreService.kt",
    "VanceCoreService.kt FloatingOrbService startForegroundService",
    r'if \(Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.O\) \{'
)

# 2c. FloatingOrbService.kt:140 — TYPE_APPLICATION_OVERLAY
path = f"{BASE}/ui/FloatingOrbService.kt"
content = read_file(path)
# This one is an inline if/else expression, not a block
old_inline = '''type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }'''
new_inline = '''type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY'''
if old_inline in content:
    content = content.replace(old_inline, new_inline)
    write_file(path, content)
    print(f"  FloatingOrbService.kt TYPE_APPLICATION_OVERLAY: stripped")
else:
    print(f"  FloatingOrbService.kt TYPE_APPLICATION_OVERLAY: pattern not found")

# 2d. OnboardingActivity.kt:213 — startForegroundService
strip_version_check(
    "ui/OnboardingActivity.kt",
    "OnboardingActivity.kt startForegroundService",
    r'if \(android\.os\.Build\.VERSION\.SDK_INT >= android\.os\.Build\.VERSION_CODES\.O\) \{'
)

# 2e. VoicePipeline.kt:202 — speak with LLOPIPOP check
path = f"{BASE}/voice/VoicePipeline.kt"
content = read_file(path)
# The LOLLIPOP check wraps textToSpeech.speak calls
# if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
#     textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
# } else {
#     @Suppress("DEPRECATION")
#     val hashParams = ...
#     @Suppress("DEPRECATION")
#     textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, hashParams)
# }
old_voice = '''        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            val hashParams = java.util.HashMap<String, String>()
            hashParams[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            @Suppress("DEPRECATION")
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, hashParams)
        }'''
new_voice = '''        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)'''
if old_voice in content:
    content = content.replace(old_voice, new_voice)
    write_file(path, content)
    print(f"  VoicePipeline.kt LOLLIPOP speak: stripped")
else:
    print(f"  VoicePipeline.kt LOLLIPOP speak: pattern not found")

# 2f. FloatingOrbService.kt:133 — canDrawOverlays M check
path = f"{BASE}/ui/FloatingOrbService.kt"
content = read_file(path)
old_m = '''        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }'''
new_m = '''        if (!Settings.canDrawOverlays(this)) {
            return
        }'''
if old_m in content:
    content = content.replace(old_m, new_m)
    write_file(path, content)
    print(f"  FloatingOrbService.kt M canDrawOverlays: stripped")
else:
    print(f"  FloatingOrbService.kt M canDrawOverlays: pattern not found")

# 2g. VanceAccessibilityService.kt:209 — < N check (never true)
path = f"{BASE}/accessibility/VanceAccessibilityService.kt"
content = read_file(path)
old_n = '''        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Swipe requires API 24+")
            return
        }
        return try {'''
new_n = '''        return try {'''
if old_n in content:
    content = content.replace(old_n, new_n)
    write_file(path, content)
    print(f"  VanceAccessibilityService.kt < N swipe: stripped")
else:
    print(f"  VanceAccessibilityService.kt < N swipe: pattern not found")

print("\n=== Done ===")
