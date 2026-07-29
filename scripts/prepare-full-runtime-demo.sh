#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
DEMO_ROOT="$PROJECT_ROOT/target/ricbot-full-demo"

if [ -e "$DEMO_ROOT" ]; then
  echo "Refusing to overwrite existing demo: $DEMO_ROOT" >&2
  echo "Move or remove that disposable directory explicitly, then run this script again." >&2
  exit 2
fi

mkdir -p "$DEMO_ROOT"
cp -R "$PROJECT_ROOT/examples/order-fulfillment-demo/." "$DEMO_ROOT/"
mkdir -p "$DEMO_ROOT/rules" "$DEMO_ROOT/fixtures"

RULES="$DEMO_ROOT/rules/fulfillment-audit-rules.md"
: > "$RULES"
rule=1
while [ "$rule" -le 420 ]; do
  printf '## RULE-%04d\nEvery accepted row must retain order id, SKU, quantity, UTC timestamp, dry-run flag, idempotency outcome, inventory before/after, and a stable audit correlation id. Invalid rows must never mutate inventory.\n\n' "$rule" >> "$RULES"
  rule=$((rule + 1))
done

cat > "$DEMO_ROOT/fixtures/orders.csv" <<'CSV'
order_id,sku,quantity
order-100,SKU-RED,2
order-101,SKU-BLUE,1
order-100,SKU-RED,2
bad-row,SKU-RED,not-a-number
order-102,SKU-MISSING,1
CSV

git -C "$DEMO_ROOT" init -q
git -C "$DEMO_ROOT" config user.name "Ricbot Demo"
git -C "$DEMO_ROOT" config user.email "ricbot-demo@example.invalid"
git -C "$DEMO_ROOT" add .
git -C "$DEMO_ROOT" commit -q -m "seed order fulfillment demo"

echo "$DEMO_ROOT"
echo "Large rule bytes: $(wc -c < "$RULES" | tr -d ' ')"
