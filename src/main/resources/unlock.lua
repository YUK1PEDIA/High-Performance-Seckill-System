-- 获取锁的标识，判断是否与当前线程标识一致
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
    -- 如果一致，就释放锁
    return redis.call("DEL", KEYS[1])
end
return 0