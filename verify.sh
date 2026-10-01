#!/usr/bin/env bash
# 端到端验证脚本：对运行中的服务走通 预约->登记->称重->复核->积分->暂存->交接->追溯 全链路
# 用法: ./verify.sh  （默认 BASE_URL=http://localhost:8080，可用 BASE_URL 环境变量覆盖）
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

check() { # $1=描述 $2=实际 $3=期望
  if [ "$2" == "$3" ]; then
    PASS=$((PASS+1)); echo "  ✔ $1"
  else
    FAIL=$((FAIL+1)); echo "  ✘ $1  期望=[$3] 实际=[$2]"
  fi
}

login() {
  curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r '.token'
}

echo "== 0. 健康检查 =="
HEALTH=$(curl -s "$BASE/api/health" | jq -r '.status')
check "服务健康" "$HEALTH" "UP"

echo "== 1. 四类角色登录 =="
T_RESIDENT=$(login resident1 resident123)
T_RECYCLER=$(login recycler1 recycler123)
T_RECYCLER2=$(login recycler2 recycler123)
T_REVIEWER=$(login reviewer1 reviewer123)
T_ADMIN=$(login admin1 admin123)
check "居民登录" "$([ -n "$T_RESIDENT" ] && [ "$T_RESIDENT" != null ] && echo ok)" "ok"
check "回收员登录" "$([ -n "$T_RECYCLER" ] && [ "$T_RECYCLER" != null ] && echo ok)" "ok"
check "复核员登录" "$([ -n "$T_REVIEWER" ] && [ "$T_REVIEWER" != null ] && echo ok)" "ok"
check "管理员登录" "$([ -n "$T_ADMIN" ] && [ "$T_ADMIN" != null ] && echo ok)" "ok"

AUTH_R=(-H "X-Auth-Token: $T_RESIDENT")
AUTH_W=(-H "X-Auth-Token: $T_RECYCLER")
AUTH_V=(-H "X-Auth-Token: $T_REVIEWER")
AUTH_A=(-H "X-Auth-Token: $T_ADMIN")
JSON=(-H 'Content-Type: application/json')

echo "== 2. 基础目录 =="
SITE_ID=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/sites" | jq -r '.[0].id')
ACT_ID=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq -r '.[0].id')
check "药品分类 6 类" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/categories" | jq 'length')" "6"
check "积分规则含高风险标记" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/points-rules" | jq '[.[] | select(.highRisk==true)] | length')" "2"
check "活动日倍率 1.5" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq -r '.[0].pointsMultiplier')" "1.5"

echo "== 3. 居民预约（活动日，含处方/液体/个人信息标识） =="
APPT=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"感冒药、安眠药、口服液\", \"estimatedQty\": 5,
  \"hasPrescription\": true, \"hasLiquid\": true, \"hasInjection\": true,
  \"hasPersonalInfo\": true, \"activityDayId\": $ACT_ID}")
APPT_NO=$(echo "$APPT" | jq -r '.appointmentNo')
check "预约单号生成" "$(echo "$APPT_NO" | grep -c '^AP')" "1"
check "积分规则快照已固化" "$(echo "$APPT" | jq -r '.pointsRuleSnapshot' | grep -c 'TABLET:10')" "1"
check "活动日名额 +1" "$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/activity-days?siteId=$SITE_ID" | jq '.[0].bookedCount')" "1"

echo "== 4. 回收员扫码登记 =="
BATCH=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" -d "{\"appointmentNo\": \"$APPT_NO\"}")
BATCH_NO=$(echo "$BATCH" | jq -r '.batchNo')
check "批次状态 CHECKED_IN" "$(echo "$BATCH" | jq -r '.status')" "CHECKED_IN"
check "批次倍率继承活动日" "$(echo "$BATCH" | jq -r '.pointsMultiplier')" "1.5"

echo "== 5. 分类称重封袋（片剂2kg + 注射器0.5kg高风险 + 液体1kg） =="
BATCH=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH_NO/weigh" -d '{
  "items": [
    {"category": "TABLET",  "weightKg": 2.0, "sealNo": "SEAL-T-1001", "photoUrls": "photo://pkg-t,photo://bag-t"},
    {"category": "SYRINGE", "weightKg": 0.5, "sealNo": "SEAL-S-1001", "photoUrls": "photo://pkg-s,photo://bag-s"},
    {"category": "LIQUID",  "weightKg": 1.0, "sealNo": "SEAL-L-1001", "photoUrls": "photo://pkg-l,photo://bag-l"}
  ]}')
STATUS=$(curl -s -X POST "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/finish-weighing" | jq -r '.status')
check "含高风险袋 -> 批次进入复核" "$STATUS" "IN_REVIEW"
# 片剂 2*10*1.5=30，液体 1*12*1.5=18，注射器待复核
check "非高风险即时积分 30+18" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "148"

echo "== 6. 人工复核高风险注射器 =="
TASK_NO=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r '.[0].taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$TASK_NO/decide" \
  -d '{"approve": true, "note": "针剂已安全封装，核对无误"}' > /dev/null
check "复核通过发放 0.5*8*1.5=6" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "154"
check "批次积分已发放" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "POINTS_GRANTED"

echo "== 7. 入暂存柜 =="
BAG_NOS=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO/trace" | jq -r '[.bags[].bag.bagNo] | @json')
CABINET_ID=$(curl -s "${AUTH_W[@]}" "$BASE/api/storage/cabinets?siteId=$SITE_ID" | jq -r '.[0].cabinet.id')
PUT=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": $BAG_NOS}")
check "入柜 3 袋" "$(echo "$PUT" | jq '.storedBags')" "3"
check "批次已入柜" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "STORED"
check "容量预警接口可用" "$(curl -s "${AUTH_A[@]}" "$BASE/api/storage/alerts?siteId=$SITE_ID" | jq 'type')" "\"array\""

echo "== 8. 管理员看板 =="
DASH=$(curl -s "${AUTH_A[@]}" "$BASE/api/admin/dashboard?siteId=$SITE_ID")
check "看板-暂存 3 袋" "$(echo "$DASH" | jq '.pickup.storedBags')" "3"
check "看板-未达上门量" "$(echo "$DASH" | jq '.pickup.thresholdReached')" "false"
check "看板-含高风险需双人" "$(echo "$DASH" | jq '.handover.requiresDualIfHandoverNow')" "true"
check "看板-超期统计存在" "$(echo "$DASH" | jq '.overdue.count')" "0"

echo "== 9. 环保交接（上门量校验 -> 双人签收 -> 完成） =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\"}")
check "未达上门量拒绝建单(409)" "$CODE" "409"
ORDER=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true}")
ORDER_NO=$(echo "$ORDER" | jq -r '.orderNo')
check "强制建单成功" "$(echo "$ORDER_NO" | grep -c '^HO')" "1"
check "含高风险需双人交接" "$(echo "$ORDER" | jq -r '.requiresDual')" "true"
check "第一人签收后仍未出库" "$(curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_NO/sign" | jq -r '.status')" "DRAFT"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER_NO/sign")
check "同一人重复签收被拒(409)" "$CODE" "409"
check "第二人签收后出库" "$(curl -s -X POST "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_NO/sign" | jq -r '.status')" "IN_TRANSIT"
DONE=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER_NO/complete" \
  -d '{"receiverName": "绿源-张司机", "photoUrls": "photo://handover-1"}')
check "交接完成" "$(echo "$DONE" | jq -r '.status')" "COMPLETED"
check "批次已交接" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH_NO" | jq -r '.status')" "HANDED_OVER"
DETAIL=$(curl -s "${AUTH_A[@]}" "$BASE/api/handovers/$ORDER_NO")
check "交接单含签收人" "$(echo "$DETAIL" | jq -r '.order.receiverName')" "绿源-张司机"
check "交接单含积分发放结果" "$(echo "$DETAIL" | jq '.pointsResult | length')" "1"
check "交接积分 54 分" "$(echo "$DETAIL" | jq '.pointsResult[0].totalPoints')" "54"

echo "== 10. 批次全链路追溯 =="
TRACE=$(curl -s "${AUTH_R[@]}" "$BASE/api/batches/$BATCH_NO/trace")
check "追溯-称重记录 3 条" "$(echo "$TRACE" | jq '.weighRecords | length')" "3"
check "追溯-每袋有来源与分类" "$(echo "$TRACE" | jq '[.bags[].bag.category] | length')" "3"
check "追溯-高风险袋有复核记录" "$(echo "$TRACE" | jq '[.bags[] | select(.bag.category=="SYRINGE") | .reviewTasks[]] | length')" "1"
check "追溯-封袋编号在案" "$(echo "$TRACE" | jq -r '[.bags[].bag.sealNo] | join(",")' | grep -c 'SEAL-S-1001')" "1"

echo "== 11. 称重争议复秤 =="
APPT2=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"阿莫西林\", \"estimatedQty\": 2,
  \"hasPrescription\": true, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}")
APPT2_NO=$(echo "$APPT2" | jq -r '.appointmentNo')
BATCH2_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT2_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH2_NO/weigh" \
  -d '{"items": [{"category": "ANTIBIOTIC", "weightKg": 2.0, "dispute": true, "photoUrls": "photo://pkg-a"}]}' > /dev/null
check "争议自动积分 2*15=30" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "184"
EX_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions?status=OPEN" | jq -r '[.[] | select(.type=="WEIGHT_DISPUTE")][0].exceptionNo')
check "称重争议异常自动生成" "$(echo "$EX_NO" | grep -c '^EX')" "1"
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/exceptions/$EX_NO/reweigh" -d '{"newWeightKg": 2.6}' > /dev/null
check "复秤补差 2.6*15=39" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "193"
check "复秤后异常已办结" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions/$EX_NO" | jq -r '.status')" "RESOLVED"

echo "== 12. 夹带未过期药 -> 扣回积分并复核拒绝 =="
BAG2_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH2_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/exceptions" \
  -d "{\"batchNo\": \"$BATCH2_NO\", \"bagNo\": \"$BAG2_NO\", \"type\": \"NON_EXPIRED_DRUG\", \"description\": \"发现未过期药品\"}" > /dev/null
check "积分已扣回" "$(curl -s "${AUTH_R[@]}" "$BASE/api/points/balance" | jq '.balance')" "154"
TASK2_NO=$(curl -s "${AUTH_V[@]}" "$BASE/api/reviews?status=PENDING" | jq -r '.[0].taskNo')
curl -s -X POST "${AUTH_V[@]}" "${JSON[@]}" "$BASE/api/reviews/$TASK2_NO/decide" \
  -d '{"approve": false, "note": "未过期药品退回居民"}' > /dev/null
check "批次关闭" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH2_NO" | jq -r '.status')" "CLOSED"

echo "== 13. 环保公司拒收 -> 回柜 + 异常 =="
APPT3_NO=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"软膏\", \"estimatedQty\": 1,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH3_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT3_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH3_NO/weigh" \
  -d '{"items": [{"category": "EXTERNAL", "weightKg": 1.0}]}' > /dev/null
BAG3_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH3_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG3_NO\"]}" > /dev/null
ORDER3_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER3_NO/sign" > /dev/null
REJ=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER3_NO/complete" \
  -d "{\"receiverName\": \"绿源-张司机\", \"items\": [{\"bagNo\": \"$BAG3_NO\", \"accepted\": false, \"rejectReason\": \"管状包装不在接收范围\"}]}")
check "部分拒收状态" "$(echo "$REJ" | jq -r '.status')" "PARTIAL_REJECTED"
check "拒收异常已记录" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions" | jq '[.[] | select(.type=="COMPANY_REJECT")] | length' | grep -c '^[1-9]')" "1"
check "拒收袋退回暂存" "$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH3_NO/trace" | jq -r '.bags[0].bag.status')" "STORED"

echo "== 14. 积分兑换（成功 + 余额不足失败） =="
GIFT_OK=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/gifts" | jq -r '[.[] | select(.costPoints<=154)][0].id')
EX1=$(curl -s -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_OK}")
check "兑换成功" "$(echo "$EX1" | jq -r '.status')" "SUCCESS"
# 余额 154-120=34，兑换 500 分体检券应失败
GIFT_EXPENSIVE=$(curl -s "${AUTH_R[@]}" "$BASE/api/catalog/gifts" | jq -r '[.[] | select(.costPoints==500)][0].id')
CODE=$(curl -s -o /tmp/ex_fail.json -w '%{http_code}' -X POST "${AUTH_R[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_EXPENSIVE}")
check "余额不足兑换失败(409)" "$CODE" "409"
check "失败单已记录" "$(jq -r '.status' /tmp/ex_fail.json)" "FAILED"

echo "== 15. 积分发放失败 -> 客服复核补发（含原因/规则/批次/账户快照，防重复补发） =="
T_SERVICE=$(login service1 service123)
LOGIN_R2=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"resident2","password":"resident123"}')
T_RESIDENT2=$(echo "$LOGIN_R2" | jq -r '.token')
RID2=$(echo "$LOGIN_R2" | jq -r '.account.id')
AUTH_S=(-H "X-Auth-Token: $T_SERVICE")
AUTH_R2=(-H "X-Auth-Token: $T_RESIDENT2")
check "客服登录" "$([ -n "$T_SERVICE" ] && [ "$T_SERVICE" != null ] && echo ok)" "ok"

# 管理员先冻结居民2账户；居民2称重 2kg 片剂应发 20 分，因账户冻结发放失败
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/admin/points-accounts/freeze" -d "{\"residentId\": $RID2}" > /dev/null
APPT4_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"普通片剂\", \"estimatedQty\": 2,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH4_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" -d "{\"appointmentNo\": \"$APPT4_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH4_NO/weigh" \
  -d '{"items": [{"category": "TABLET", "weightKg": 2.0, "sealNo": "SEAL-PI-2001", "photoUrls": "photo://pi"}]}' > /dev/null
PI1_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH4_NO/trace" | jq -r '.bags[0].pointsIssue.issueNo')
check "冻结账户称重产生发放失败单" "$(echo "$PI1_NO" | grep -c '^PI')" "1"
PI1=$(curl -s "${AUTH_S[@]}" "$BASE/api/points-issues/$PI1_NO")
check "失败单待复核" "$(echo "$PI1" | jq -r '.issue.status')" "PENDING"
check "失败原因=账户冻结" "$(echo "$PI1" | jq -r '.issue.failReason')" "ACCOUNT_FROZEN"
check "失败时账户状态快照" "$(echo "$PI1" | jq -r '.issue.accountStatusAtFailure')" "FROZEN"
check "失败单固化积分规则快照" "$(echo "$PI1" | jq -r '.issue.pointsRuleSnapshot' | grep -c 'TABLET:10')" "1"
check "失败单关联批次" "$(echo "$PI1" | jq -r '.issue.batchNo')" "$BATCH4_NO"
check "客服可见是否已兑换(否)" "$(echo "$PI1" | jq -r '.residentEverExchanged')" "false"
check "失败期积分未入账" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "0"
check "发放失败异常自动登记" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions" | jq '[.[] | select(.type=="POINTS_GRANT_FAILED")] | length' | grep -c '^[1-9]')" "1"
# 冻结期间补发应被拒(409)
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/points-issues/$PI1_NO/reissue" -d '{"note":"尝试补发"}')
check "账户冻结期补发被拒(409)" "$CODE" "409"
# 解冻后补发
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/admin/points-accounts/unfreeze" -d "{\"residentId\": $RID2}" > /dev/null
curl -s -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/points-issues/$PI1_NO/reissue" -d '{"note":"核实后补发"}' > /dev/null
check "补发后状态 REISSUED" "$(curl -s "${AUTH_S[@]}" "$BASE/api/points-issues/$PI1_NO" | jq -r '.issue.status')" "REISSUED"
check "补发积分 20 入账" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"
check "补发异常联动办结" "$(curl -s "${AUTH_W[@]}" "$BASE/api/exceptions?status=RESOLVED" | jq "[.[] | select(.type==\"POINTS_GRANT_FAILED\")] | length" | grep -c '^[1-9]')" "1"
# 防重复补发
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/points-issues/$PI1_NO/reissue" -d '{"note":"再次补发"}')
check "重复补发被拒(409)" "$CODE" "409"
check "防重复补发余额不变" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"
check "居民台账可见失败单" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/ledger" | jq '[.issues[] | select(.issueNo=="'$PI1_NO'")] | length')" "1"

echo "== 16. 客服改为纸质兑换券（不入账户，线下兑付防重复） =="
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/admin/points-accounts/freeze" -d "{\"residentId\": $RID2}" > /dev/null
APPT5_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"普通片剂\", \"estimatedQty\": 3,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH5_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" -d "{\"appointmentNo\": \"$APPT5_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH5_NO/weigh" \
  -d '{"items": [{"category": "TABLET", "weightKg": 3.0, "sealNo": "SEAL-PV-2002", "photoUrls": "photo://pv"}]}' > /dev/null
PI2_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH5_NO/trace" | jq -r '.bags[0].pointsIssue.issueNo')
curl -s -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/points-issues/$PI2_NO/voucher" -d '{"note":"账户冻结改纸质券"}' > /dev/null
check "失败单转券 VOUCHERED" "$(curl -s "${AUTH_S[@]}" "$BASE/api/points-issues/$PI2_NO" | jq -r '.issue.status')" "VOUCHERED"
PV_NO=$(curl -s "${AUTH_S[@]}" "$BASE/api/points-issues/$PI2_NO" | jq -r '.issue.voucherNo')
check "兑换券面值 30" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/vouchers/$PV_NO" | jq '.pointsValue')" "30"
check "转券不入积分账户" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"
check "居民可见本人兑换券" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/vouchers/mine" | jq '[.[] | select(.voucherNo=="'$PV_NO'")] | length')" "1"
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/vouchers/$PV_NO/redeem" -d '{"note":"社区点已兑付"}' > /dev/null
check "兑换券已兑付" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/vouchers/$PV_NO" | jq -r '.status')" "REDEEMED"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/vouchers/$PV_NO/redeem" -d '{"note":"重复兑付"}')
check "重复兑付被拒(409)" "$CODE" "409"
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/admin/points-accounts/unfreeze" -d "{\"residentId\": $RID2}" > /dev/null

echo "== 17. 批次拒收 -> 已发积分冻结/追缴（余额不足记欠分，后续入账自动抵扣） =="
# 居民2称重 5kg 发放 50，余额 70；兑换 50 分环保袋，余 20
APPT6_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"片剂整箱\", \"estimatedQty\": 5,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH6_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" -d "{\"appointmentNo\": \"$APPT6_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH6_NO/weigh" \
  -d '{"items": [{"category": "TABLET", "weightKg": 5.0, "sealNo": "SEAL-FZ-2003", "photoUrls": "photo://fz"}]}' > /dev/null
check "发放 50 后余额 70" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "70"
GIFT50_ID=$(curl -s "${AUTH_R2[@]}" "$BASE/api/catalog/gifts" | jq -r '[.[] | select(.costPoints==50)][0].id')
curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT50_ID}" > /dev/null
check "兑换后余额 20" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"
BAG6_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH6_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG6_NO\"]}" > /dev/null
ORDER6_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true, \"bagNos\": [\"$BAG6_NO\"]}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER6_NO/sign" > /dev/null
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER6_NO/complete" \
  -d "{\"receiverName\": \"绿源-张司机\", \"items\": [{\"bagNo\": \"$BAG6_NO\", \"accepted\": false, \"rejectReason\": \"整箱混装不在接收范围\"}]}" > /dev/null
FZ_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH6_NO/trace" | jq -r '.bags[0].pointsFreeze.freezeNo')
check "拒收产生冻结单" "$(echo "$FZ_NO" | grep -c '^FZ')" "1"
check "当场冻结 20" "$(curl -s "${AUTH_A[@]}" "$BASE/api/points-freezes/$FZ_NO" | jq '.freeze.frozenPoints')" "20"
check "不足形成追缴欠分 30" "$(curl -s "${AUTH_A[@]}" "$BASE/api/points-freezes/$FZ_NO" | jq '.freeze.outstandingPoints')" "30"
check "拒收冻结后可用余额 0" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "0"
check "居民可见本人冻结单" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points-freezes/mine" | jq '[.[] | select(.freezeNo=="'$FZ_NO'")] | length')" "1"
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/points-freezes/$FZ_NO/basis" \
  -d '{"rejectedCategory":"TABLET","handlingBasis":"依据《社区过期药品回收积分管理办法》第12条，拒收品类已发积分冻结追缴"}' > /dev/null
check "管理员登记拒收品类与依据" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points-freezes/$FZ_NO" | jq -r '.freeze.handlingBasis' | grep -c '第12条')" "1"
# 主动追缴当前可用 0：欠分不变
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/points-freezes/$FZ_NO/recover" -d '{"note":"催缴"}' > /dev/null
check "无可用余额追缴欠分不变" "$(curl -s "${AUTH_A[@]}" "$BASE/api/points-freezes/$FZ_NO" | jq '.freeze.outstandingPoints')" "30"
# 后续称重发放 3kg=30 自动抵扣欠分 30，欠分偿清冻结单核销
APPT7_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"片剂\", \"estimatedQty\": 3,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH7_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" -d "{\"appointmentNo\": \"$APPT7_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH7_NO/weigh" \
  -d '{"items": [{"category": "TABLET", "weightKg": 3.0, "sealNo": "SEAL-RC-2004", "photoUrls": "photo://rc"}]}' > /dev/null
check "入账自动抵扣后欠分清零" "$(curl -s "${AUTH_A[@]}" "$BASE/api/points-freezes/$FZ_NO" | jq '.freeze.outstandingPoints')" "0"
check "冻结单全额追缴核销" "$(curl -s "${AUTH_A[@]}" "$BASE/api/points-freezes/$FZ_NO" | jq -r '.freeze.status')" "RECOVERED"
check "累计追缴 50" "$(curl -s "${AUTH_A[@]}" "$BASE/api/points-freezes/$FZ_NO" | jq '.freeze.recoveredPoints')" "50"

echo "== 18. 申诉成立解除冻结退回积分 =="
# 居民2再称重 2kg 发放 20（欠分已清，全额入账）-> 余额 20
APPT8_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"片剂\", \"estimatedQty\": 2,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH8_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" -d "{\"appointmentNo\": \"$APPT8_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH8_NO/weigh" \
  -d '{"items": [{"category": "TABLET", "weightKg": 2.0, "sealNo": "SEAL-RL-2005", "photoUrls": "photo://rl"}]}' > /dev/null
check "新批次发放 20 入账" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"
BAG8_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH8_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG8_NO\"]}" > /dev/null
ORDER8_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"force\": true, \"bagNos\": [\"$BAG8_NO\"]}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER8_NO/sign" > /dev/null
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER8_NO/complete" \
  -d "{\"receiverName\": \"绿源-张司机\", \"items\": [{\"bagNo\": \"$BAG8_NO\", \"accepted\": false, \"rejectReason\": \"外包装破损\"}]}" > /dev/null
FZ2_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH8_NO/trace" | jq -r '.bags[0].pointsFreeze.freezeNo')
check "余额充足全额冻结 20" "$(curl -s "${AUTH_A[@]}" "$BASE/api/points-freezes/$FZ2_NO" | jq '.freeze.frozenPoints')" "20"
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/points-freezes/$FZ2_NO/release" -d '{"note":"环保公司误拒收，申诉成立"}' > /dev/null
check "申诉解冻状态 RELEASED" "$(curl -s "${AUTH_A[@]}" "$BASE/api/points-freezes/$FZ2_NO" | jq -r '.freeze.status')" "RELEASED"
check "解冻积分退回可用 20" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "20"

echo ""
echo "==================================="
echo "验证完成: 通过 $PASS 项, 失败 $FAIL 项"
echo "==================================="
[ "$FAIL" -eq 0 ]
