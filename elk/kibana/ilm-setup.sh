#!/bin/bash
# Kibana 기동 후 실행: ILM 정책 + 인덱스 템플릿 세팅
# 사용법: ./elk/kibana/ilm-setup.sh
# 또는 docker exec paycore-kibana bash /setup/ilm-setup.sh

ES="http://elasticsearch:9200"
MAX_RETRY=30

echo "[ILM Setup] Elasticsearch 기동 대기 중..."
for i in $(seq 1 $MAX_RETRY); do
  if curl -sf "$ES/_cluster/health" > /dev/null 2>&1; then
    echo "[ILM Setup] Elasticsearch 준비 완료"
    break
  fi
  echo "  대기 중... ($i/$MAX_RETRY)"
  sleep 5
done

# ─────────────────────────────────────────────
# 1. ILM 정책: 30일 후 자동 삭제
# ─────────────────────────────────────────────
echo "[ILM Setup] ILM 정책 생성 중..."
curl -sf -X PUT "$ES/_ilm/policy/paycore-30day-policy" \
  -H 'Content-Type: application/json' \
  -d '{
    "policy": {
      "phases": {
        "hot": {
          "actions": {
            "rollover": {
              "max_size": "5gb",
              "max_age": "1d"
            }
          }
        },
        "warm": {
          "min_age": "3d",
          "actions": {
            "forcemerge": { "max_num_segments": 1 },
            "shrink": { "number_of_shards": 1 }
          }
        },
        "delete": {
          "min_age": "30d",
          "actions": {
            "delete": {}
          }
        }
      }
    }
  }' && echo " → ILM 정책 완료"

# ─────────────────────────────────────────────
# 2. 인덱스 템플릿: 앱 로그
# ─────────────────────────────────────────────
echo "[ILM Setup] 앱 로그 인덱스 템플릿 생성 중..."
curl -sf -X PUT "$ES/_index_template/paycore-app-logs" \
  -H 'Content-Type: application/json' \
  -d '{
    "index_patterns": ["paycore-app-logs-*"],
    "template": {
      "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0,
        "index.lifecycle.name": "paycore-30day-policy",
        "index.lifecycle.rollover_alias": "paycore-app-logs"
      },
      "mappings": {
        "properties": {
          "@timestamp":     { "type": "date" },
          "level":          { "type": "keyword" },
          "logger":         { "type": "keyword" },
          "merchantId":     { "type": "keyword" },
          "requestId":      { "type": "keyword" },
          "traceId":        { "type": "keyword" },
          "spanId":         { "type": "keyword" },
          "log_category":   { "type": "keyword" },
          "alert_candidate":{ "type": "boolean" },
          "message":        { "type": "text" },
          "app":            { "type": "keyword" },
          "env":            { "type": "keyword" }
        }
      }
    }
  }' && echo " → 앱 로그 템플릿 완료"

# ─────────────────────────────────────────────
# 3. 인덱스 템플릿: payment_logs (비즈니스 대시보드)
# ─────────────────────────────────────────────
echo "[ILM Setup] payment_logs 인덱스 템플릿 생성 중..."
curl -sf -X PUT "$ES/_index_template/paycore-payment-logs" \
  -H 'Content-Type: application/json' \
  -d '{
    "index_patterns": ["paycore-payment-logs-*"],
    "template": {
      "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0,
        "index.lifecycle.name": "paycore-30day-policy",
        "index.lifecycle.rollover_alias": "paycore-payment-logs"
      },
      "mappings": {
        "properties": {
          "@timestamp":       { "type": "date" },
          "id":               { "type": "long" },
          "merchant_order_id":{ "type": "keyword" },
          "log_type":         { "type": "keyword" },
          "success":          { "type": "boolean" },
          "error_message":    { "type": "text" }
        }
      }
    }
  }' && echo " → payment_logs 템플릿 완료"

# ─────────────────────────────────────────────
# 4. 초기 인덱스 생성 (ILM alias 부트스트랩)
# ─────────────────────────────────────────────
echo "[ILM Setup] 초기 인덱스(alias) 생성 중..."
for ALIAS in paycore-app-logs paycore-payment-logs; do
  FIRST_INDEX="${ALIAS}-000001"
  curl -sf -X PUT "$ES/$FIRST_INDEX" \
    -H 'Content-Type: application/json' \
    -d "{
      \"aliases\": {
        \"$ALIAS\": { \"is_write_index\": true }
      }
    }" 2>/dev/null || true
done
echo " → 초기 인덱스 완료"

echo ""
echo "[ILM Setup] 설정 완료. Kibana(http://localhost:5601)에서 확인하세요."
echo "  Stack Management → Index Lifecycle Policies → paycore-30day-policy"
echo "  Stack Management → Index Templates → paycore-app-logs, paycore-payment-logs"
