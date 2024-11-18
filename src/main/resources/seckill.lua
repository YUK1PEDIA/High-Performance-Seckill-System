-- 优惠券 id
local voucherId = ARGV[1]
-- 用户 id
local userId = ARGV[2]
-- 订单 id
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足
    return 1
end

-- 判断用户是否下单
if (redis.call('sismember', orderKey, userId) == 1) then
    -- redis 中存的用户 id 存在，说明该用户重复下单
    return 2
end

-- 扣库存
redis.call('incrby', stockKey, -1)
-- 下单（保存用户）
redis.call('sadd', orderKey, userId)
-- 发送消息到队列中
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
-- 返回 0 代表成功
return 0