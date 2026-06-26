package com.erp.finance.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.finance.entity.FinAp;
import com.erp.finance.mapper.FinApMapper;
import com.erp.finance.service.FinApService;
import org.springframework.stereotype.Service;

@Service
public class FinApServiceImpl extends ServiceImpl<FinApMapper, FinAp> implements FinApService {
}
