package com.sanye.strategy.common.base;

import java.io.Serializable;
import java.util.Collection;

public interface IService<T> extends IBaseService<T> {
    boolean insertBatch(Collection<T> entityList);
    boolean updateBatch(Collection<T> entityList);
    boolean deleteBatch(Collection<? extends Serializable> idList);
    boolean saveOrUpdateBatch(Collection<T> entityList);
}