import json
import subprocess
import sys

BASE_URL = "http://localhost:8084/__baafoo__/api"
API_KEY = "staging-admin-key"
RULES_FILE = sys.argv[1] if len(sys.argv) > 1 else "testing/2_IntegrationTest/rules/all-protocols-rules.json"

try:
    with open(RULES_FILE, "r", encoding="utf-8") as f:
        rules = json.load(f)
except FileNotFoundError:
    print(f"Rules file not found: {RULES_FILE}")
    sys.exit(1)

print(f"Registering {len(rules)} rules from {RULES_FILE} ...")
for rule in rules:
    rule_json = json.dumps(rule, ensure_ascii=False)
    result = subprocess.run(
        [
            "curl", "-sf", "-X", "POST", f"{BASE_URL}/rules",
            "-H", "Content-Type: application/json",
            "-H", f"X-Api-Key: {API_KEY}",
            "-d", rule_json,
        ],
        capture_output=True,
        text=True,
    )
    print(f"{rule['id']}: {result.stdout or result.stderr}")

print("Done.")
