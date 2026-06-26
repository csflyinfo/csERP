package com.erp.base.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.base.entity.BaseGoods;
import com.erp.base.mapper.BaseGoodsMapper;
import com.erp.base.service.BaseGoodsService;
import org.springframework.stereotype.Service;

@Service
public class BaseGoodsServiceImpl extends ServiceImpl<BaseGoodsMapper, BaseGoods> implements BaseGoodsService {
}
