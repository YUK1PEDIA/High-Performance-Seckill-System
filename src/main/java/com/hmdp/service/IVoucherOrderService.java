package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 优惠券秒杀下单
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建优惠券订单
     *
     * @param voucherId
     */
    void createVoucherOrder(VoucherOrder voucherId);
}
