#!/bin/bash
# Register all protocol rules to Baafoo Server
API="http://localhost:8084/__baafoo__/api/rules"
KEY="staging-admin-key"

register_rule() {
  local file=$1
  echo "Registering: $file"
  curl.exe -s -X POST "$API" -H "Content-Type: application/json" -H "X-Api-Key: $KEY" -d "@$file"
  echo ""
}

# HTTP rules
register_rule "test-rules/rules/http-get.json"
register_rule "test-rules/rules/http-post.json"
register_rule "test-rules/rules/http-put.json"
register_rule "test-rules/rules/http-delete.json"
register_rule "test-rules/rules/http-delay.json"
register_rule "test-rules/rules/http-error.json"
register_rule "test-rules/rules/http-staging-b.json"
register_rule "test-rules/rules/http-consul.json"

# Kafka rules
register_rule "test-rules/rules/kafka-topic.json"
register_rule "test-rules/rules/kafka-wildcard.json"

# Pulsar rules
register_rule "test-rules/rules/pulsar-topic.json"

# JMS rules
register_rule "test-rules/rules/jms-queue.json"
register_rule "test-rules/rules/jms-topic.json"

# TCP rules
register_rule "test-rules/rules/tcp-hex.json"
register_rule "test-rules/rules/tcp-regex.json"
register_rule "test-rules/rules/tcp-multiround.json"

echo "Done! All rules registered."
