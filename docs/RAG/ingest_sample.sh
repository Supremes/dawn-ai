#!/bin/bash
# Sample document corpus for RAG ingest.
# Covers 5 domains: e-commerce policy, finance, healthcare, legal, and product engineering.
# Useful for testing cross-category retrieval, metadata filtering, and reranking quality.

BASE_URL="http://localhost:8080/api/v1/rag"

declare -a DOCS=(

  # ── E-Commerce ───────────────────────────────────────────────────────────────
  '{"content":"Our return policy allows customers to return any item within 30 days of purchase for a full refund, provided the item is in its original condition and packaging. Electronics must be returned within 15 days.","source":"ecommerce-policy","category":"policy"}'
  '{"content":"Customers can track their orders in real-time through the order management portal. Shipping updates are sent via email and SMS at each logistics checkpoint, from warehouse dispatch to final delivery.","source":"ecommerce-support","category":"shipping"}'
  '{"content":"Flash sale events run every Friday from 12:00 to 14:00 UTC. Products are discounted up to 70%. Items added to cart before the sale ends are guaranteed at the sale price even if checkout completes later.","source":"ecommerce-promotions","category":"promotion"}'

  # ── Finance ──────────────────────────────────────────────────────────────────
  '{"content":"A stop-loss order automatically sells a security when its price falls to a specified threshold, limiting the investor downside. Trailing stop-loss orders adjust the threshold as the price rises.","source":"finance-glossary","category":"trading"}'
  '{"content":"Dollar-cost averaging (DCA) is an investment strategy where a fixed amount is invested at regular intervals regardless of asset price. Over time this reduces the impact of volatility on the overall purchase price.","source":"finance-guide","category":"investment"}'
  '{"content":"Credit scores are calculated from payment history (35%), amounts owed (30%), length of credit history (15%), new credit (10%), and credit mix (10%). Scores range from 300 to 850.","source":"finance-education","category":"credit"}'

  # ── Healthcare ───────────────────────────────────────────────────────────────
  '{"content":"Type 2 diabetes is managed through lifestyle changes including diet, exercise, and weight management. Metformin is typically the first-line medication, often combined with GLP-1 receptor agonists for better glycemic control.","source":"medical-reference","category":"endocrinology"}'
  '{"content":"Hypertension is diagnosed when blood pressure consistently exceeds 130/80 mmHg. First-line treatment includes DASH diet, sodium reduction, aerobic exercise, and ACE inhibitors or ARBs if lifestyle changes are insufficient.","source":"medical-reference","category":"cardiology"}'
  '{"content":"Cognitive behavioral therapy (CBT) is a structured, goal-oriented psychotherapy that addresses negative thought patterns. It is the evidence-based first-line treatment for generalized anxiety disorder and depression.","source":"mental-health-guide","category":"psychology"}'

  # ── Legal ────────────────────────────────────────────────────────────────────
  '{"content":"Under GDPR, data controllers must obtain explicit, informed consent before processing personal data. Consent must be freely given, specific, and withdrawable at any time without penalty.","source":"legal-compliance","category":"data-privacy"}'
  '{"content":"Non-disclosure agreements (NDAs) protect confidential business information shared between parties. Mutual NDAs bind both parties, while unilateral NDAs protect only the disclosing party. Breach can result in injunctive relief and damages.","source":"legal-templates","category":"contract"}'
  '{"content":"Intellectual property rights include patents, trademarks, copyrights, and trade secrets. Patents grant a 20-year exclusive right to an invention; copyrights protect original works automatically upon creation.","source":"legal-education","category":"intellectual-property"}'

  # ── Product Engineering ──────────────────────────────────────────────────────
  '{"content":"The strangler fig pattern migrates a monolith incrementally by routing new functionality through a facade. Over time, the facade replaces the monolith piece by piece until the legacy system can be decommissioned.","source":"engineering-patterns","category":"architecture"}'
  '{"content":"Circuit breaker pattern prevents cascading failures in distributed systems. When a downstream service exceeds the configured error threshold, the circuit opens and requests fail fast, giving the service time to recover.","source":"engineering-patterns","category":"reliability"}'
  '{"content":"Blue-green deployment maintains two identical production environments. Traffic is shifted from the blue (current) environment to the green (new) environment atomically, allowing instant rollback by redirecting traffic back.","source":"devops-guide","category":"deployment"}'
  '{"content":"A/B testing exposes two variants of a feature to separate user segments simultaneously. Statistical significance is measured with a two-proportion z-test; a p-value below 0.05 is typically required before declaring a winner.","source":"product-guide","category":"experimentation"}'
  '{"content":"OpenTelemetry provides a vendor-neutral standard for distributed tracing, metrics, and logs. Traces propagate context via W3C TraceContext headers, enabling end-to-end request visibility across microservices.","source":"observability-guide","category":"monitoring"}'
)

TOTAL=${#DOCS[@]}
echo "=== Batch Ingest ($TOTAL documents) ==="
SUCCESS=0
FAIL=0

for i in "${!DOCS[@]}"; do
  resp=$(curl -s -X POST "$BASE_URL/ingest" \
    -H "Content-Type: application/json" \
    -d "${DOCS[$i]}")
  docId=$(echo "$resp" | python3 -c "import json,sys; r=json.load(sys.stdin); print(r.get('docId','ERR'))" 2>/dev/null)
  status=$(echo "$resp" | python3 -c "import json,sys; r=json.load(sys.stdin); print(r.get('status','ERR'))" 2>/dev/null)
  if [[ "$status" == "ingested" ]]; then
    echo "  [$(( i+1 ))/$TOTAL] ✓  docId=$docId"
    (( SUCCESS++ ))
  else
    echo "  [$(( i+1 ))/$TOTAL] ✗  response=$resp"
    (( FAIL++ ))
  fi
done

echo ""
echo "Done: $SUCCESS succeeded, $FAIL failed."
