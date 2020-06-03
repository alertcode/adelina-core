package top.alertcode.adelina.framework.base.service;


import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.alertcode.adelina.framework.base.commons.constant.CommonsConstants;
import top.alertcode.adelina.framework.base.dao.CommonsDao;
import top.alertcode.adelina.framework.base.entity.*;
import top.alertcode.adelina.framework.base.exception.VerficationException;
import top.alertcode.adelina.framework.base.inteface.IDBDataEncrypt;
import top.alertcode.adelina.framework.base.struct.WhereCondition;
import top.alertcode.adelina.framework.base.utils.CommonsUtils;
import top.alertcode.adelina.framework.cache.TableCacheDao;
import top.alertcode.adelina.framework.utils.*;

import java.util.*;

/**
 * Created by zhangxu on 2018/6/19.
 */
@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class CommonsService implements BeanFactoryAware {

    private static BeanFactory beanFactory;

    @Autowired
    private CommonsDao commonsDao;
    @Autowired
    private TableCacheDao tableCacheDao;
    @Autowired(required = false)
    private IDBDataEncrypt idbDataEncrypt;


    /**
     * 初始化BeanFactory
     * @param bFactory
     * @throws BeansException
     */
    @Override
    public void setBeanFactory(BeanFactory bFactory) throws BeansException {
        if (beanFactory == null && bFactory != null) {
            CommonsService.beanFactory = bFactory;
        }
    }

    /***
     *  将指定类型的javaBean数据插入到数据库中
     * @param dataObject
     * @param <T>
     * @return
     */

    public <T> T insertData(T dataObject) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(dataObject.getClass());
        this.fillBeanInitValues(dataObject);
        //数据加密
        dataFilter(dataObject, entitySqlTable, true);
        commonsDao.insert(entitySqlTable, dataObject);
        //数据解密
        dataFilter(dataObject, entitySqlTable, false);
        //将数据更新到缓存中
        addDataToCache(entitySqlTable, dataObject);

        return dataObject;
    }


    /***
     * 将指定类型的javaBean数据插入到数据库中(循环插入数据，可以更新缓存）
     * @param dataList
     * @param <T>
     * @return
     */

    public <T> Integer insertDataList(List<T> dataList) {
        Integer cnt = 0;
        if (dataList != null && dataList.size() > 0) {
            EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(dataList.get(0).getClass());
            for (T t : dataList) {
                //数据加密
                dataFilter(t, entitySqlTable, true);
                this.fillBeanInitValues(t);
                cnt += commonsDao.insert(entitySqlTable, t);
                //数据解密
                dataFilter(t, entitySqlTable, false);
                //将数据更新到缓存中
                addListDataToCache(entitySqlTable, dataList);
            }
        }
        return cnt;
    }

    /***
     * 批量将数据集合插入到数据库中(批量直接插入，效率高）
     * @param dataList
     * @param <T>
     * @return
     */

    public <T> Integer batchInsertListData(List<T> dataList) {
        Integer cnt = 0;
        if (dataList != null && dataList.size() > 0) {
            EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(dataList.get(0).getClass());
            List<List<T>> listArray = Lists.partition(dataList, 200);
            for (List<T> itemList : listArray) {
                for (T t : itemList) {
                    //数据加密
                    dataFilter(t, entitySqlTable, true);
                    this.fillBeanInitValues(t);
                    //数据解密
                    dataFilter(t, entitySqlTable, false);
                }
                cnt += commonsDao.insertList(entitySqlTable, itemList);
            }
        }
        return cnt;
    }

    /***
     * 更新数据库数据
     * @param dataObject
     * @param <T>
     * @return
     */

    public <T> Integer updateData(T dataObject) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(dataObject.getClass());
        //数据加密
        dataFilter(dataObject, entitySqlTable, true);
        //更新时添加更新者更新时间
        this.fillBeanUpdateValues(dataObject);
        Integer cnt = commonsDao.update(entitySqlTable, dataObject);
        if (cnt == 0) {
            log.debug("updateData fail: {}", dataObject);
            throw new VerficationException("更新失败");
        }
        //数据解密
        dataFilter(dataObject, entitySqlTable, false);
        //更新缓存中的业务数据
        updateDataToCache(entitySqlTable, dataObject);
        return cnt;
    }

    /***
     * 数据过滤操作
     * @param dataObject
     * @param entitySqlTable
     * @param isDataIn        true：输入数据(加密),false:输出数据(解密)
     * @param <T>
     */
    public <T> T dataFilter(T dataObject, EntitySqlTable entitySqlTable, boolean isDataIn) {
        AssertUtils.notNull(entitySqlTable, "实体映射对象不能为空。");
        AssertUtils.notNull(dataObject, "数据对象不能为空。");
        if (idbDataEncrypt == null) {
            log.debug("---------------------------------：为设置数据加密,不做任何处理");
            return dataObject;
        }
        for (EntitySqlColumn sqlColumn : entitySqlTable.getSqlColumns()) {
            if (Boolean.TRUE.equals(sqlColumn.getEncrypt())) {
                //输入数据（数据保存到数据库中加密）
                if (isDataIn) {
                    String fieldValue = BeanUtils.getPropertyValue(dataObject, sqlColumn.getAttributeName());
                    String encryptString = idbDataEncrypt.encrypt(fieldValue);
                    BeanUtils.setPropertyValue(dataObject, sqlColumn.getAttributeName(), encryptString);
                    //从数据库中获取数据(对数据进行解密)
                } else {
                    String fieldValue = BeanUtils.getPropertyValue(dataObject, sqlColumn.getAttributeName());
                    String decryptString = idbDataEncrypt.decrypt(fieldValue);
                    BeanUtils.setPropertyValue(dataObject, sqlColumn.getAttributeName(), decryptString);
                }
            }
        }
        return dataObject;

    }

    /***
     * 数据过滤操作(数据加解密操作)
     * @param dataObject
     * @param beanClass
     * @param isDataIn        true：输入数据,false:输出数据
     * @param <T>
     */
    public <T> T queryDataFilter(T dataObject, Class beanClass, boolean isDataIn) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(beanClass);
        if (dataObject instanceof PageInfo) {
            PageInfo pageInfo = (PageInfo) dataObject;
            if (CollectionUtils.isNotEmpty(pageInfo.getList())) {
                pageInfo.getList().stream().forEach(item -> {
                    dataFilter(item, entitySqlTable, isDataIn);
                });
            }
        } else {
            dataFilter(dataObject, entitySqlTable, isDataIn);
        }
        return dataObject;

    }

    /***
     *
     * 更新数据库数据。如果字段为空，则不处理该字段。
     * <B>能够记录操作日志</B>
     * @param dataObject
     * @param <T>
     * @return
     */

    public <T> Integer forEachUpdateExcludeNull(T dataObject) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(dataObject.getClass());
        T dbEntity = (T) selectById(dataObject.getClass(), BeanUtils.getPropertyValue(dataObject, "id"));
        //判断传递过来的属性是否为空，为空使用原始数据
        for (EntitySqlColumn sqlColumn : entitySqlTable.getSqlColumns()) {
            Object value = BeanUtils.getPropertyValue(dataObject, sqlColumn.getAttributeName());
            if (value != null) {
                BeanUtils.setPropertyValue(dbEntity, sqlColumn.getAttributeName(), value);
            }
        }
        //数据加密
        dataFilter(dataObject, entitySqlTable, true);
        Integer cnt = commonsDao.update(entitySqlTable, dbEntity);
        if (cnt == 0) {
            log.debug("forEachUpdateExcludeNull fail: {}", dataObject);
            throw new VerficationException("更新失败");
        }
        dataFilter(dataObject, entitySqlTable, false);
        //更新缓存中的业务数据
        updateDataToCache(entitySqlTable, dbEntity);
        return cnt;
    }

    /***
     * 更新数据库数据。如果字段为空，则不处理该字段。
     * <B>不能够记录操作日志</B>
     * @param dataObject
     * @param <T>
     * @return
     */

    public <T> Integer updateExcludeNull(T dataObject) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(dataObject.getClass());
        //更新时添加更新者更新时间
        this.fillBeanUpdateValues(dataObject);
        //数据加密
        dataFilter(dataObject, entitySqlTable, true);
        Integer cnt = commonsDao.updateExcludeNull(entitySqlTable, dataObject);
        if (cnt == 0) {
            log.debug("updateExcludeNull fail: {}", dataObject);
            throw new VerficationException("更新失败");
        }
        dataFilter(dataObject, entitySqlTable, false);
        //更新缓存中的业务数据
        updateDataToCache(entitySqlTable, dataObject);
        return cnt;
    }

    /***
     * 更新数据库数据。如果字段为空，则不处理该字段。
     * <B>不能够记录操作日志</B>
     * @param dataObject
     * @param whereCondition  更新条件
     * @param <T>
     * @return
     */

    public <T> Integer updateExcludeNullWhere(T dataObject, WhereCondition whereCondition) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(dataObject.getClass());
        //数据加密
        dataFilter(dataObject, entitySqlTable, true);
        //更新时添加更新者更新时间
        this.fillBeanUpdateValues(dataObject);
        Integer cnt = commonsDao.updateExcludeNullWhere(entitySqlTable, dataObject, whereCondition);
        if (cnt == 0) {
            log.debug("updateExcludeNullWhere fail: {}", dataObject);
            throw new VerficationException("更新失败");
        }
        dataFilter(dataObject, entitySqlTable, false);
        //更新缓存中的业务数据
        updateDataToCache(entitySqlTable, dataObject, whereCondition);
        return cnt;
    }

    /**
     * 删除数据库数据
     * @param tClass 类型
     * @param id 数据主键Id
     */

    public Integer deleteById(Class tClass, Long id) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        return commonsDao.delete(entitySqlTable, id);
    }

    /**
     * 删除数据库数据
     * @param tClass 类型
     * @param whereString 删除数据条件 "user_name ='John'"
     */

    public Integer deleteByWhereString(Class tClass, String whereString) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        removeDataFromCacheCondition(entitySqlTable, whereString);
        return commonsDao.deleteByWhereString(entitySqlTable, whereString);
    }


    /**
     * 删除数据库数据
     * @param tClass 类型
     * @param whereCondition 删除数据条件
     */

    public Integer deleteByCondition(Class tClass, WhereCondition whereCondition) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        removeDataFromCacheCondition(entitySqlTable, whereCondition.toString());
        return commonsDao.deleteByCondition(entitySqlTable, whereCondition);
    }

    /**
     * 删除数据库数据
     * @param tClass
     */

    public <T> T selectById(Class<T> tClass, Long id) {
        return findDataById(tClass, id, 1);
    }


    /***
     * 查询数据库数据
     * @param tClass
     * @param whereString 查询条件
     * @param <T>
     * @return
     */

    public <T> List<T> selectByWhereString(Class tClass, String whereString) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        List<Map<String, Object>> result = commonsDao.selectByWhereString(entitySqlTable, whereString);
        return convertToBeanList(result, tClass);
    }

    /***
     * 查询数据库数据
     * @param tClass
     * @param whereString 查询条件
     * @param pageNum 页码
     * @return
     */

    public <T> PageInfo<T> selectByWhereString(Class<T> tClass, Integer pageNum, String whereString) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        PageHelper.startPage(pageNum, CommonsConstants.PAGE_SIZE);
        List<Map<String, Object>> result = commonsDao.selectByWhereString(entitySqlTable, whereString);
        return new PageInfo<>(convertToBeanList(result, tClass));
    }

    /***
     * 查询数据库数据
     * @param tClass
     * @param pageSize 分页条数
     * @param pageNum 页码
     * @param whereString 查询条件
     * @return
     */

    public <T> PageInfo<T> selectByCondition(Class<T> tClass, Integer pageSize, Integer pageNum, String whereString) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        setPageInfo(pageSize, pageNum);
        List<Map<String, Object>> result = commonsDao.selectByWhereString(entitySqlTable, whereString);
        return new PageInfo<>(convertToBeanList(result, tClass));
    }

    /***
     * 查询数据库数据
     * @param tClass
     * @param whereCondition 查询条件
     * @param <T>
     * @return
     */

    public <T> T selectOneByCondition(Class tClass, WhereCondition whereCondition) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        List<Map<String, Object>> result = commonsDao.selectByCondition(entitySqlTable, whereCondition);
        if (result == null || result.size() == 0) {
            return null;
        } else if (result.size() == 1) {
            T t = (T) BeanUtils.mapToBean(convertBeanProperty(result.get(0)), tClass);
            //数据解密
            dataFilter(t, entitySqlTable, false);
            return t;
        } else {
            throw new IllegalArgumentException("查询返回多条数据");
        }
    }

    /***
     * 查询数据库数据
     * @param tClass
     * @param whereCondition 查询条件
     * @param <T>
     * @return
     */

    public <T> List<T> selectByCondition(Class tClass, WhereCondition whereCondition) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        List<Map<String, Object>> result = commonsDao.selectByCondition(entitySqlTable, whereCondition);
        return convertToBeanList(result, tClass);
    }

    /***
     * 查询数据库数据
     * @param tClass
     * @param whereCondition 查询条件
     * @param pageNum 页码
     * @return
     */

    public <T> PageInfo<T> selectByCondition(Class<T> tClass, Integer pageNum, WhereCondition whereCondition) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        if (pageNum == null) {
            PageHelper.startPage(1, Integer.MAX_VALUE);
        } else {
            PageHelper.startPage(pageNum, CommonsConstants.PAGE_SIZE);
        }
        List<Map<String, Object>> result = commonsDao.selectByCondition(entitySqlTable, whereCondition);
        return new PageInfo<>(convertToBeanList(result, tClass));
    }

    /***
     * 查询数据库数据
     * @param tClass
     * @param pageSize 分页条数
     * @param pageNum 页码
     * @param whereCondition 查询条件
     * @return
     */

    public <T> PageInfo<T> selectByCondition(Class<T> tClass, Integer pageSize, Integer pageNum, WhereCondition whereCondition) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        setPageInfo(pageSize, pageNum);
        List<Map<String, Object>> result = commonsDao.selectByCondition(entitySqlTable, whereCondition);
        return new PageInfo<>(convertToBeanList(result, tClass));
    }

    /***
     * 根据查询条件获取数据条数
     * @param tClass
     * @param whereCondition 查询条件
     * @return
     */

    public <T> int selectCountCondition(Class<T> tClass, WhereCondition whereCondition) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        Integer count = commonsDao.selectCountCondition(entitySqlTable, whereCondition);
        return count;
    }

    /***
     *  获取当期登陆用户信息
     * @return
     */

    // @Deprecated
    // public CurrentEmployee getCurrentUser() {
    //     try {
    //         return currentEmployeeService.getCurrentEmployee(HttpContextUtils.getRequest());
    //     } catch (Exception xe) {
    //         return null;
    //     }
    // }

    /***
     * 当前登录用户的UUID，全局唯一
     * @return
     */
    // public String getCurrentUserID() {
    //     String UUID = SqlBusinessEvents.getOperatorUUID();
    //     if (StringUtils.isNotBlank(UUID)) {
    //         return UUID;
    //     }
    //     CurrentEmployee currentEmployee = getCurrentUser();
    //     Integer userId = currentEmployee == null ? null : currentEmployee.getId();
    //     if (userId == null) {
    //         throw new IllegalArgumentException("无法获取当前用户信息");
    //     }
    //     return String.valueOf(userId);
    // }

    /***
     *  获取当期登陆用户信息(原版基础平台使用）
     * @param  defaultUserName 当期用户为null时返回defaultUserName
     * @return
     */

    public String getCurrentUserName(String defaultUserName) {
        //是否使用新版IT平台
        // String userUuid = SqlBusinessEvents.getOperatorUUID();
        // if (StringUtils.isNotBlank(userUuid)) {
        //     return SqlBusinessEvents.getOperator();
        // }
        //使用原始IT平台，通过原来的方式获取用户名
        // CurrentEmployee currentEmployee = getCurrentUser();
        // if (currentEmployee == null) {
        //     return defaultUserName;
        // } else {
        //     return currentEmployee.getEmpName();
        // }
        return defaultUserName;
    }


    /***
     *  获取当期登陆用户信息（新版基础平台使用）
     * @param  defaultUserName 当期用户为null时返回defaultUserName
     * @return
     */

    public String getCurrentUserNameV2(String defaultUserName) {
        // String userName = SqlBusinessEvents.getOperator();
        String userName = "";
        if (StringUtils.isBlank(userName)) {
            return defaultUserName;
        }
        return userName;
    }

    /**
     * 获取JavaBean对象
     * @param name Bean名称
     * @param <T>  Bean类型
     * @return
     */
    public static <T> T getBean(String name) {
        return (T) beanFactory.getBean(name);
    }

    /**
     * 获取JavaBean对象
     * @param cls Bean类型
     * @param <T>  Bean类型
     * @return
     */
    public static <T> T getBean(Class cls) {
        return (T) beanFactory.getBean(cls);
    }

    /***
     * 返回当前用户登陆的Token值
     * @return
     */
    // public String getUserToken() {
    //     String token = HttpContextUtils.getRequest().getParameter("token");
    //     return token;
    // }

    /***
     * 填充Entity共同属性值（createdAt,createdBy,updatedBy,updatedAt)
     * @param entity
     * @param <T>
     */
    public <T> void fillBeanInitValues(T entity) {
        String currentUserName = getCurrentUserName("GuestUser");
        if (entity instanceof BaseEntity) {
            BaseEntity dataEntity = (BaseEntity) entity;
            ((BaseEntity) entity).setId(null);
            ((BaseEntity) entity).setVersion(1);
            if (Objects.isNull(dataEntity.getCreatedAt()) || Objects.isNull(dataEntity.getVersion())) {
                dataEntity.setVersion(1);
                dataEntity.setCreatedBy(currentUserName);
                dataEntity.setUpdatedBy(currentUserName);
                dataEntity.setCreatedAt(DateUtils.now());
                dataEntity.setUpdatedAt(DateUtils.now());
            }
        } else {
            Object version = BeanUtils.getPropertyValue(entity, "version");
            Object createdAt = BeanUtils.getPropertyValue(entity, "createdAt");
            if (Objects.isNull(version) || Objects.isNull(createdAt)) {
                BeanUtils.setPropertyValue(entity, "version", 1);
                BeanUtils.setPropertyValue(entity, "createdAt", DateUtils.now());
                BeanUtils.setPropertyValue(entity, "createdBy", currentUserName);
                BeanUtils.setPropertyValue(entity, "updatedAt", DateUtils.now());
                BeanUtils.setPropertyValue(entity, "updatedBy", currentUserName);
            }
        }

    }

    /***
     * 填充Entity更新时的属性值（updatedBy,updatedAt)
     * @param entity
     * @param <T>
     */
    public <T> void fillBeanUpdateValues(T entity) {
        String currentUserName = getCurrentUserName("GuestUser");
        if (entity instanceof BaseEntity) {
            BaseEntity dataEntity = (BaseEntity) entity;
            dataEntity.setUpdatedBy(currentUserName);
            dataEntity.setUpdatedAt(DateUtils.now());
        } else {
            BeanUtils.setPropertyValue(entity, "updatedAt", DateUtils.now());
            BeanUtils.setPropertyValue(entity, "updatedBy", currentUserName);
        }

    }

    //=================================================================================================================
    //=================================================================================================================

    /**
     * 删除数据库数据
     * @param tClass
     * @param id     数据主键ID
     * @param sourceType  1：先从缓存中获取，缓存中没有再从数据库中获取，2:直接从数据库中获取
     */
    private <T> T findDataById(Class<T> tClass, Long id, int sourceType) {
        long begin = System.currentTimeMillis();
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(tClass);
        if (sourceType == 1) {
            //获取缓存中的数据
            T t = getDataFromCache(entitySqlTable, String.valueOf(id));
            if (t != null) {
                long end0 = System.currentTimeMillis();
                long useTime0 = end0 - begin;
                //log.info("----------------------------------命中缓存 耗时：{}",useTime0);
                return t;
            }
        }
        long begin1 = System.currentTimeMillis();
        Map<String, Object> data = commonsDao.selectById(entitySqlTable, id);
        long end2 = System.currentTimeMillis();
        long useTime0 = begin1 - begin;
        long useTime1 = end2 - begin1;
        //log.info("----------------------------------未命中缓存，从数据库中获取数据 前置操作耗时：{}，查询操作耗时：{}",useTime0,useTime1);
        if (data == null) {
            return null;
        }
        long begin2 = System.currentTimeMillis();
        T t = (T) BeanUtils.mapToBean(data, tClass);
        long end3 = System.currentTimeMillis();
        //如果在缓存中没有，在数据库中有数据，将查询的数据添加到缓存中
        addDataToCache(entitySqlTable, t);
        long end4 = System.currentTimeMillis();
        long useTime3 = end3 - begin2;
        long useTime4 = end4 - end3;

        long begin3 = System.currentTimeMillis();
        T t1 = (T) JsonUtils.toBean(JsonUtils.toJson(data), tClass);
        long end5 = System.currentTimeMillis();
        long useTime5 = end5 - begin3;
        //log.info("----------------------------------Bean转换耗时：{}，添加缓存耗时：{},JsonUtils转换耗时：{}",useTime3,useTime4,useTime5);

        //数据解密
        dataFilter(t, entitySqlTable, false);
        return t;
    }


    /**
     * 获取实体类的ID值
     * @param entity
     * @return
     */
    private String getId(Object entity) {
        if (entity instanceof BaseEntity) {
            return ((BaseEntity) entity).getId().toString();
        } else {
            return BeanUtils.getPropertyValue(entity, "id").toString();
        }
    }

    /**
     * 将数据添加到缓存中
     * @param entitySqlTable
     * @param dataObject
     * @param <T>
     */
    private <T> void addDataToCache(EntitySqlTable entitySqlTable, T dataObject) {
        //将数据添加到缓存中
        if (entitySqlTable.getUserCache()) {
            tableCacheDao.add(CommonsUtils.getCacheKey(entitySqlTable.getEntityClass()), getId(dataObject), JsonUtils.toJson(dataObject));
        }
    }

    /**
     * 将数据添加到缓存中
     * @param entitySqlTable
     * @param dataListObject
     * @param <T>
     */
    private <T> void addListDataToCache(EntitySqlTable entitySqlTable, List<T> dataListObject) {
        //将数据添加到缓存中
        if (entitySqlTable.getUserCache()) {
            for (T item : dataListObject) {
                tableCacheDao.add(CommonsUtils.getCacheKey(entitySqlTable.getEntityClass()), getId(item), JsonUtils.toJson(item));
            }
        }
    }

    /**
     * 更新缓存中的数据
     * @param entitySqlTable
     * @param dataObject
     * @param <T>
     */
    private <T> void updateDataToCache(EntitySqlTable entitySqlTable, T dataObject, WhereCondition whereCondition) {
        //将数据添加到缓存中(如果数据已经存在，先删除再添加)
        if (entitySqlTable.getUserCache()) {
            List<T> list = this.selectByCondition(entitySqlTable.getEntityClass(), whereCondition);
            if (CollectionUtils.isEmpty(list)) {
                for (T item : list) {
                    String key = getId(dataObject);
                    //删除缓存中的数据
                    removeDataFromCache(entitySqlTable, key);
                    //从数据库中获取数据
                    if (item == null) {
                        continue;
                    }
                    tableCacheDao.add(CommonsUtils.getCacheKey(entitySqlTable.getEntityClass()), key, JsonUtils.toJson(item));
                }
            }
        }
    }


    /**
     * 更新缓存中的数据
     * @param entitySqlTable
     * @param dataObject
     * @param <T>
     */
    private <T> void updateDataToCache(EntitySqlTable entitySqlTable, T dataObject) {
        //将数据添加到缓存中(如果数据已经存在，先删除再添加)
        if (entitySqlTable.getUserCache()) {
            String key = getId(dataObject);
            //删除缓存中的数据
            removeDataFromCache(entitySqlTable, key);
            //从数据库中获取数据
            T object = (T) this.findDataById(entitySqlTable.getEntityClass(), Long.parseLong(key), 2);
            if (object == null) {
                log.error("不能正确从数据库中获取数据：className:{} ,Id:{}", entitySqlTable.getClassName(), key);
                throw new IllegalArgumentException("更新缓存失败");
            }
            tableCacheDao.add(CommonsUtils.getCacheKey(entitySqlTable.getEntityClass()), key, JsonUtils.toJson(object));
        }
    }

    /***
     * 从缓存中获取数据
     * @param entitySqlTable 实体类型
     * @param key       Id值
     * @param <T>
     * @return
     */
    private <T> T getDataFromCache(EntitySqlTable entitySqlTable, String key) {
        if (entitySqlTable.getUserCache()) {
            Object data = tableCacheDao.get(CommonsUtils.getCacheKey(entitySqlTable.getEntityClass()), key);
            if (Objects.isNull(data)) {
                return null;
            }
            T object = (T) data;
            return object;
        }
        return null;
    }

    /***
     *  删除缓存中的数据
     * @param entitySqlTable
     * @param key
     */
    private void removeDataFromCache(EntitySqlTable entitySqlTable, String key) {
        if (entitySqlTable.getUserCache()) {
            String tableName = CommonsUtils.getCacheKey(entitySqlTable.getEntityClass());
            if (tableCacheDao.exists(tableName, key)) {
                tableCacheDao.delete(tableName, key);
            }
        }
    }

    /***
     *  根据条件删除缓存中的数据
     * @param entitySqlTable
     * @param condition
     */
    private void removeDataFromCacheCondition(EntitySqlTable entitySqlTable, String condition) {
        if (entitySqlTable.getUserCache()) {
            List list = selectByWhereString(entitySqlTable.getEntityClass(), condition);
            String tableName = CommonsUtils.getCacheKey(entitySqlTable.getEntityClass());
            for (Object item : list) {
                String key = ((Object) BeanUtils.getPropertyValue(item, "id")).toString();
                if (tableCacheDao.exists(tableName, key)) {
                    tableCacheDao.delete(tableName, key);
                }
            }
        }
    }

    /***
     * 将数据库的查询结果List<Map>转换成List<Bean>形式
     * @param list
     * @param classType
     * @param <T>
     * @return
     */
    private <T> List<T> convertToBeanList(List<?> list, Class<T> classType) {
        EntitySqlTable entitySqlTable = EntitySqlFactory.getClassMap(classType);
        List<T> result = new ArrayList<T>();
        if (CollectionUtils.isEmpty(list)) {
            return result;
        }
        for (Object objItem : list) {
            Map<String, Object> item = (Map<String, Object>) objItem;
            Map<String, Object> beanItem = convertBeanProperty(item);
            T dataItem = BeanUtils.mapToBean(beanItem, classType);
            //数据解密
            dataFilter(dataItem, entitySqlTable, false);
            result.add(dataItem);
        }
        if (list instanceof Page) {
            list.clear();
            ((Page) list).addAll(result);
            return (List<T>) list;
        }
        return result;
    }


    /***
     * 将数据库字段值替换成Java属性名，即：user_code => userCode
     * @param queryResult
     * @return
     */
    private Map<String, Object> convertBeanProperty(Map<String, Object> queryResult) {
        if (MapUtils.isEmpty(queryResult)) {
            return queryResult;
        }
        Map<String, Object> result = new HashedMap();
        for (Map.Entry<String, Object> item : queryResult.entrySet()) {
            String key = item.getKey();
            Object value = item.getValue();
            if (key.indexOf('_') > 0) {
                key = key == null ? null : key.toLowerCase();
                key = CommonsUtils.removePropertyUnderline(key);
            }
            result.put(key, value);
        }
        return result;
    }


    /**
     * 设置分页详情
     * @param pageSize  默认值= 10
     * @param pageNum 默认值= 1
     */
    private void setPageInfo(Integer pageSize, Integer pageNum) {
        PageHelper.startPage(pageNum == null || pageNum < 1 ? 1 : pageNum,
                pageSize == null || pageSize < 1 ? CommonsConstants.PAGE_SIZE : pageSize);
    }

    /**
     * 通用条件查询
     * @param baseSql
     * @param orderBySql
     * @return
     */
    public <T> List<T> listBaseEntity(BaseEntitySql baseSql, OrderBySqlBase orderBySql) {
        orderBySql.sqlSimple();
        List<Object> list = (List<Object>) (Object) commonsDao.listBaseEntity(baseSql, orderBySql);
        List<T> tmp = new ArrayList<>();
        for (Object o : list) {
            // 防止缓存
            if (o instanceof Map) {
                T dataItem = (T) BeanUtils.mapToBean((Map<String, Object>) o, baseSql.getEntitySqlTable("").getEntityClass());
                //数据解密
                dataFilter(dataItem, baseSql.getEntitySqlTable(""), false);
                tmp.add((T) dataItem);
            } else {
                tmp.add((T) o);
            }
        }
        list.clear();
        list.addAll(tmp);
        return (List<T>) (T) list;
    }

    /**
     * 通用条件查询
     * @param baseSql
     * @param orderBySql
     * @return
     */
    public <T> T oneBaseEntity(BaseEntitySql baseSql, OrderBySqlBase orderBySql) {
        orderBySql.sqlSimple();
        Map map = commonsDao.oneBaseEntity(baseSql, orderBySql);
        if (map != null) {
            T dataItem = (T) BeanUtils.mapToBean((Map<String, Object>) map, baseSql.getEntitySqlTable("").getEntityClass());
            //数据解密
            dataFilter(dataItem, baseSql.getEntitySqlTable(""), false);
            return dataItem;
        } else {
            return null;
        }

    }
}
