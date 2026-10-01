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
T_RESIDENT2=$(login resident2 resident123)
T_RECYCLER=$(login recycler1 recycler123)
T_RECYCLER2=$(login recycler2 recycler123)
T_REVIEWER=$(login reviewer1 reviewer123)
T_SERVICE=$(login service1 service123)
T_ADMIN=$(login admin1 admin123)
check "居民登录" "$([ -n "$T_RESIDENT" ] && [ "$T_RESIDENT" != null ] && echo ok)" "ok"
check "回收员登录" "$([ -n "$T_RECYCLER" ] && [ "$T_RECYCLER" != null ] && echo ok)" "ok"
check "复核员登录" "$([ -n "$T_REVIEWER" ] && [ "$T_REVIEWER" != null ] && echo ok)" "ok"
check "客服登录" "$([ -n "$T_SERVICE" ] && [ "$T_SERVICE" != null ] && echo ok)" "ok"
check "管理员登录" "$([ -n "$T_ADMIN" ] && [ "$T_ADMIN" != null ] && echo ok)" "ok"

AUTH_R=(-H "X-Auth-Token: $T_RESIDENT")
AUTH_R2=(-H "X-Auth-Token: $T_RESIDENT2")
AUTH_W=(-H "X-Auth-Token: $T_RECYCLER")
AUTH_V=(-H "X-Auth-Token: $T_REVIEWER")
AUTH_S=(-H "X-Auth-Token: $T_SERVICE")
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

echo "== 15. 账户冻结致称重积分发放失败 -> 客服补发（防重复补发/已换标志） =="
R2_ID=$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.residentId')
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/points/accounts/$R2_ID/status" \
  -d '{"status": "FROZEN", "reason": "风控临时冻结"}' > /dev/null
check "账户已冻结" "$(curl -s "${AUTH_S[@]}" "$BASE/api/points/accounts/$R2_ID" | jq -r '.status')" "FROZEN"
APPT4_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"钙片\", \"estimatedQty\": 3,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH4_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT4_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH4_NO/weigh" \
  -d '{"items": [{"category": "TABLET", "weightKg": 5.0, "photoUrls": "photo://gf-1"}]}' > /dev/null
check "冻结期间积分不入账" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "0"
GF_NO=$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures?status=PENDING" | jq -r "[.[] | select(.residentId==$R2_ID)][0].failureNo")
check "发放失败单生成" "$(echo "$GF_NO" | grep -c '^GF')" "1"
check "失败单留存积分规则快照" "$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures/$GF_NO" | jq -r '.failure.pointsRuleSnapshot' | grep -c 'TABLET:10')" "1"
check "失败单记录账户冻结状态" "$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures/$GF_NO" | jq -r '.failure.accountStatus')" "FROZEN"
check "居民来源视图显示待补发" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/sources" | jq -r "[.sources[] | select(.pointsState==\"NOT_GRANTED\")] | length")" "1"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/grant-failures/$GF_NO/reissue" -d '{"note":"试补发"}')
check "账户冻结时补发被拒(409)" "$CODE" "409"
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/points/accounts/$R2_ID/status" \
  -d '{"status": "ACTIVE", "reason": "复核解除"}' > /dev/null
curl -s -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/grant-failures/$GF_NO/reissue" \
  -d '{"note":"核实称重记录无误，予以补发"}' > /dev/null
check "补发后积分到账 5*10=50" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "50"
check "失败单状态已补发" "$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures/$GF_NO" | jq -r '.failure.status')" "REISSUED"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/grant-failures/$GF_NO/reissue" -d '{}')
check "重复补发被拒(409)" "$CODE" "409"
EX_R2=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_OK}")
check "补发积分可兑换" "$(echo "$EX_R2" | jq -r '.status')" "SUCCESS"
check "客服可见补发积分已被兑换" "$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures/$GF_NO" | jq -r '.exchangedAfterReissue')" "true"

echo "== 16. 拒收已发积分 -> 冻结 -> 管理员追缴（依据+告知） =="
BAG4_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH4_NO/trace" | jq -r '.bags[0].bag.bagNo')
APPT5_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"软膏\", \"estimatedQty\": 2,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH5_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT5_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH5_NO/weigh" \
  -d '{"items": [{"category": "EXTERNAL", "weightKg": 2.0}]}' > /dev/null
BAG5_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH5_NO/trace" | jq -r '.bags[0].bag.bagNo')
check "外用药积分已发 2*6=12" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq '.balance')" "12"
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG5_NO\"]}" > /dev/null
ORDER5_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"bagNos\": [\"$BAG5_NO\"], \"force\": true}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER5_NO/sign" > /dev/null
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER5_NO/complete" \
  -d "{\"receiverName\": \"绿源-张司机\", \"items\": [{\"bagNo\": \"$BAG5_NO\", \"accepted\": false, \"rejectReason\": \"管装外用药不在接收名录\"}]}" > /dev/null
check "拒收后积分冻结" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.frozenPoints')" "12"
check "冻结积分不可兑换(可用0)" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.availablePoints')" "0"
check "来源视图显示冻结与拒收信息" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/sources" | jq -r "[.sources[] | select(.bagNo==\"$BAG5_NO\")][0].pointsState")" "FROZEN"
EX5_NO=$(curl -s "${AUTH_A[@]}" "$BASE/api/exceptions?status=OPEN" | jq -r "[.[] | select(.type==\"COMPANY_REJECT\" and .bagNo==\"$BAG5_NO\")][0].exceptionNo")
check "拒收异常记录拒收品类" "$(curl -s "${AUTH_A[@]}" "$BASE/api/exceptions/$EX5_NO" | jq -r '.rejectCategory')" "EXTERNAL"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/exceptions/$EX5_NO/reject-handling" -d '{"action":"CLAWBACK","regulationBasis":"  "}')
check "无处理依据拒绝追缴(400)" "$CODE" "400"
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/exceptions/$EX5_NO/reject-handling" -d "{
  \"action\": \"CLAWBACK\",
  \"regulationBasis\": \"《社区回收拒收名录》2026版 第3.2条 管装外用药不予接收\"}" > /dev/null
check "追缴后余额清零" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.balance')" "0"
check "追缴异常已办结" "$(curl -s "${AUTH_A[@]}" "$BASE/api/exceptions/$EX5_NO" | jq -r '.status')" "RESOLVED"
check "居民告知含拒收品类与依据" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/exceptions/mine" | jq -r "[.[] | select(.exceptionNo==\"$EX5_NO\")][0].residentNotice" | grep -c '第3.2条')" "1"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/exceptions/$EX5_NO/reject-handling" -d '{"action":"CLAWBACK","regulationBasis":"x"}')
check "已办结不可重复处置(409)" "$CODE" "409"

echo "== 17. 拒收申述成立 -> 解冻恢复；兑换失败自动落异常 =="
APPT6_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"口服液\", \"estimatedQty\": 1,
  \"hasPrescription\": false, \"hasLiquid\": true, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH6_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT6_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH6_NO/weigh" \
  -d '{"items": [{"category": "LIQUID", "weightKg": 1.0}]}' > /dev/null
BAG6_NO=$(curl -s "${AUTH_W[@]}" "$BASE/api/batches/$BATCH6_NO/trace" | jq -r '.bags[0].bag.bagNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/storage/put" \
  -d "{\"cabinetId\": $CABINET_ID, \"bagNos\": [\"$BAG6_NO\"]}" > /dev/null
ORDER6_NO=$(curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/handovers" \
  -d "{\"siteId\": $SITE_ID, \"companyName\": \"绿源环保科技\", \"destination\": \"市危废处理中心\", \"bagNos\": [\"$BAG6_NO\"], \"force\": true}" | jq -r '.orderNo')
curl -s -X POST "${AUTH_W[@]}" "$BASE/api/handovers/$ORDER6_NO/sign" > /dev/null
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/handovers/$ORDER6_NO/complete" \
  -d "{\"receiverName\": \"绿源-张司机\", \"items\": [{\"bagNo\": \"$BAG6_NO\", \"accepted\": false, \"rejectReason\": \"误判为非回收品类\"}]}" > /dev/null
check "液体药积分被冻结 12" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.frozenPoints')" "12"
EX6_NO=$(curl -s "${AUTH_A[@]}" "$BASE/api/exceptions?status=OPEN" | jq -r "[.[] | select(.bagNo==\"$BAG6_NO\")][0].exceptionNo")
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/exceptions/$EX6_NO/reject-handling" -d "{
  \"action\": \"UNFREEZE\",
  \"regulationBasis\": \"《社区积分争议处理办法》第5条 复核为可回收品类\",
  \"residentNotice\": \"经复核，该批次为可回收液体药，12 积分已恢复可用。\"}" > /dev/null
check "申述成立解冻恢复" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.frozenPoints')" "0"
check "解冻后余额恢复 12" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.balance')" "12"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/points/exchange" -d "{\"giftId\": $GIFT_OK}")
check "可用积分不足兑换失败(409)" "$CODE" "409"
check "兑换失败自动登记异常" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/exceptions/mine" | jq '[.[] | select(.type=="EXCHANGE_FAILED")] | length' | grep -c '^[1-9]')" "1"
check "看板待补发计数存在" "$(curl -s "${AUTH_A[@]}" "$BASE/api/admin/dashboard?siteId=$SITE_ID" | jq 'has("pendingGrantFailures")')" "true"

echo "== 18. 发放失败改纸质兑换券 / 驳回异常积分 =="
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/points/accounts/$R2_ID/status" \
  -d '{"status": "FROZEN", "reason": "二次风控"}' > /dev/null
APPT7_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"片剂\", \"estimatedQty\": 1,
  \"hasPrescription\": false, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH7_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT7_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH7_NO/weigh" \
  -d '{"items": [{"category": "TABLET", "weightKg": 1.0}]}' > /dev/null
GF7_NO=$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures?status=PENDING" | jq -r "[.[] | select(.residentId==$R2_ID)][0].failureNo")
curl -s -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/grant-failures/$GF7_NO/paper-voucher" \
  -d '{"voucherNo":"PV-2026-1001","note":"居民选择纸质兑换券"}' > /dev/null
check "纸券处理生成券号" "$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures/$GF7_NO" | jq -r '.failure.voucherNo')" "PV-2026-1001"
check "纸券不入电子账户(余额仍12)" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.balance')" "12"
check "来源视图可见纸券" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/sources" | jq -r "[.sources[] | select(.pointsState==\"PAPER_VOUCHER\")][0].grantFailure.voucherNo")" "PV-2026-1001"
APPT8_NO=$(curl -s -X POST "${AUTH_R2[@]}" "${JSON[@]}" "$BASE/api/appointments" -d "{
  \"siteId\": $SITE_ID, \"drugTypeDesc\": \"抗生素\", \"estimatedQty\": 1,
  \"hasPrescription\": true, \"hasLiquid\": false, \"hasInjection\": false, \"hasPersonalInfo\": false}" | jq -r '.appointmentNo')
BATCH8_NO=$(curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/check-in" \
  -d "{\"appointmentNo\": \"$APPT8_NO\"}" | jq -r '.batchNo')
curl -s -X POST "${AUTH_W[@]}" "${JSON[@]}" "$BASE/api/batches/$BATCH8_NO/weigh" \
  -d '{"items": [{"category": "ANTIBIOTIC", "weightKg": 1.0}]}' > /dev/null
GF8_NO=$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures?status=PENDING" | jq -r "[.[] | select(.residentId==$R2_ID)][0].failureNo")
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/grant-failures/$GF8_NO/reject" -d '{"note":" "}')
check "驳回无说明被拒(400)" "$CODE" "400"
curl -s -X POST "${AUTH_S[@]}" "${JSON[@]}" "$BASE/api/grant-failures/$GF8_NO/reject" \
  -d '{"note":"核实为异常积分，不予发放"}' > /dev/null
check "异常积分已驳回" "$(curl -s "${AUTH_S[@]}" "$BASE/api/grant-failures/$GF8_NO" | jq -r '.failure.status')" "REJECTED"
check "驳回后余额不变(仍12)" "$(curl -s "${AUTH_R2[@]}" "$BASE/api/points/balance" | jq -r '.balance')" "12"
curl -s -X POST "${AUTH_A[@]}" "${JSON[@]}" "$BASE/api/points/accounts/$R2_ID/status" \
  -d '{"status": "ACTIVE", "reason": "演示结束解除"}' > /dev/null

echo ""
echo "==================================="
echo "验证完成: 通过 $PASS 项, 失败 $FAIL 项"
echo "==================================="
[ "$FAIL" -eq 0 ]
