#!/usr/bin/env bash
set -euo pipefail

OS_URL="${OS_URL:-http://127.0.0.1:39200}"
INDEX_NAME="${AC_INDEX_NAME:-ac_candidates_stay_v1}"
READ_ALIAS="${AC_READ_ALIAS:-ac_read}"
WRITE_ALIAS="${AC_WRITE_ALIAS:-ac_write}"

printf '[ac-bootstrap] opensearch=%s index=%s\n' "$OS_URL" "$INDEX_NAME"

if ! curl -sS -I "$OS_URL/$INDEX_NAME" | head -n 1 | grep -q ' 200 '; then
curl -sS -XPUT "$OS_URL/$INDEX_NAME" \
  -H 'Content-Type: application/json' \
  -d @- <<'JSON' >/dev/null
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0,
    "analysis": {
      "tokenizer": {
        "ac_edge": {
          "type": "edge_ngram",
          "min_gram": 1,
          "max_gram": 20,
          "token_chars": ["letter", "digit"]
        }
      },
      "analyzer": {
        "ac_text": {
          "tokenizer": "ac_edge",
          "filter": ["lowercase", "asciifolding"]
        },
        "ac_text_std": {
          "tokenizer": "standard",
          "filter": ["lowercase", "asciifolding"]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "type": {"type": "keyword"},
      "canonical_id": {"type": "keyword"},
      "display_name": {
        "type": "text",
        "fields": {
          "keyword": {"type": "keyword"},
          "ac": {"type": "text", "analyzer": "ac_text", "search_analyzer": "ac_text_std"}
        }
      },
      "display_name_ko": {
        "type": "text",
        "fields": {
          "ac": {"type": "text", "analyzer": "ac_text", "search_analyzer": "ac_text_std"}
        }
      },
      "aliases": {"type": "text", "analyzer": "ac_text", "search_analyzer": "ac_text_std"},
      "country": {"type": "keyword"},
      "region": {"type": "keyword"},
      "geo": {"type": "geo_point"},
      "is_blocked": {"type": "boolean"},
      "weight": {"type": "double"},
      "ctr_7d": {"type": "double"},
      "popularity_7d": {"type": "long"},
      "updated_at": {"type": "date"}
    }
  }
}
JSON
fi

curl -sS -XPOST "$OS_URL/_aliases" \
  -H 'Content-Type: application/json' \
  -d "{\"actions\":[{\"add\":{\"index\":\"$INDEX_NAME\",\"alias\":\"$READ_ALIAS\"}},{\"add\":{\"index\":\"$INDEX_NAME\",\"alias\":\"$WRITE_ALIAS\"}}]}" >/dev/null

seed_file="$(mktemp)"
cleanup() {
  rm -f "$seed_file"
}
trap cleanup EXIT

append_doc() {
  local doc_id="$1"
  local payload="$2"
  printf '{"index":{"_index":"%s","_id":"%s"}}\n' "$WRITE_ALIAS" "$doc_id" >>"$seed_file"
  printf '%s\n' "$payload" >>"$seed_file"
}

append_doc 'city:Seoul' '{"type":"CITY","canonical_id":"Seoul","display_name":"Seoul","display_name_ko":"서울","aliases":["seoul","서울"],"country":"KR","region":"Seoul","geo":{"lat":37.5665,"lon":126.9780},"is_blocked":false,"weight":2.0,"ctr_7d":0.03,"popularity_7d":12000}'
append_doc 'city:Busan' '{"type":"CITY","canonical_id":"Busan","display_name":"Busan","display_name_ko":"부산","aliases":["busan","부산"],"country":"KR","region":"Busan","geo":{"lat":35.1796,"lon":129.0756},"is_blocked":false,"weight":1.8,"ctr_7d":0.025,"popularity_7d":9000}'
append_doc 'city:Jeju' '{"type":"CITY","canonical_id":"Jeju","display_name":"Jeju","display_name_ko":"제주","aliases":["jeju","제주"],"country":"KR","region":"Jeju","geo":{"lat":33.4996,"lon":126.5312},"is_blocked":false,"weight":1.5,"ctr_7d":0.021,"popularity_7d":6400}'
append_doc 'airport:ICN' '{"type":"AIRPORT","canonical_id":"ICN","display_name":"Incheon International Airport","display_name_ko":"인천국제공항","aliases":["ICN","인천공항"],"country":"KR","region":"Incheon","geo":{"lat":37.4602,"lon":126.4407},"is_blocked":false,"weight":1.1,"ctr_7d":0.012,"popularity_7d":4100}'
append_doc 'station:SEOUL_STATION' '{"type":"STATION","canonical_id":"SEOUL_STATION","display_name":"Seoul Station","display_name_ko":"서울역","aliases":["서울역","seoul station"],"country":"KR","region":"Seoul","geo":{"lat":37.5547,"lon":126.9706},"is_blocked":false,"weight":1.05,"ctr_7d":0.01,"popularity_7d":3800}'

for i in $(seq 1 30); do
  append_doc "property:10$i" "{\"type\":\"PROPERTY\",\"canonical_id\":\"10$i\",\"display_name\":\"Seoul Riverside Hotel $i\",\"display_name_ko\":\"서울 리버사이드 호텔 $i\",\"aliases\":[\"seoul riverside $i\",\"서울호텔$i\"],\"country\":\"KR\",\"region\":\"Seoul\",\"geo\":{\"lat\":37.56,\"lon\":126.98},\"is_blocked\":false,\"weight\":1.4,\"ctr_7d\":0.011,\"popularity_7d\":$((1800 - i * 12))}"
done

for i in $(seq 1 20); do
  append_doc "poi:20$i" "{\"type\":\"POI\",\"canonical_id\":\"20$i\",\"display_name\":\"Busan Market Spot $i\",\"display_name_ko\":\"부산 쇼핑 스팟 $i\",\"aliases\":[\"busan shopping $i\",\"부산쇼핑$i\"],\"country\":\"KR\",\"region\":\"Busan\",\"geo\":{\"lat\":35.17,\"lon\":129.07},\"is_blocked\":false,\"weight\":1.2,\"ctr_7d\":0.009,\"popularity_7d\":$((1500 - i * 9))}"
done

curl -sS -XPOST "$OS_URL/_bulk" \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary "@$seed_file" >/dev/null

printf '[ac-bootstrap] seeded docs: %s\n' "$(($(wc -l <"$seed_file") / 2))"
printf '[ac-bootstrap] done\n'
