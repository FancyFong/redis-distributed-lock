package com.fancy.redisDistributedLock.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author: fangdaji
 * @date: 2019/4/23 18:03
 * @description: 模拟商品秒杀接口
 */
@RestController
@RequestMapping(value = "/spike")
public class SpikeController {

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * redis加锁
     *
     * @param key
     * @param value 当前时间 + 超时时间
     * @return
     */
    private boolean lock(String key, String value) {
        // redis setnx
        if (redisTemplate.opsForValue().setIfAbsent(key, value)) {
            return true;
        }

        // 解决死锁
        String currentValue = redisTemplate.opsForValue().get(key);
        // 如果锁过期
        if (!StringUtils.isEmpty(currentValue) && Long.parseLong(currentValue) < System.currentTimeMillis()) {

            // 获取上一个锁的时间 多线程情况下，只有一个线程拿到锁
            String oldValue = redisTemplate.opsForValue().getAndSet(key, value);
            if (!StringUtils.isEmpty(currentValue) && oldValue.equals(value)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 解锁
     *
     * @param key
     * @param value
     */
    private void unlock(String key, String value) {
        String currentValue = redisTemplate.opsForValue().get(key);
        if (!StringUtils.isEmpty(currentValue) && currentValue.equals(value)) {
            redisTemplate.opsForValue().getOperations().delete(key);
        }
    }


    /**
     * 国庆活动，皮蛋粥特价，限量100000份
     */
    static Map<String, Integer> products;
    static Map<String, Integer> stock;
    static Map<String, String> orders;

    static {
        products = new HashMap<>();
        stock = new HashMap<>();
        orders = new HashMap<>();
        products.put("1", 100000);
        stock.put("1", 100000);
    }

    private String queryMap(String productId) {
        return "国庆活动，皮蛋粥特价，限量份" +
                products.get(productId) +
                " 还剩： " + stock.get(productId) + "份" +
                " 该商品成功下单用户数目：" +
                orders.size() + "人";
    }


    /**
     * 查询接口
     *
     * @param productId
     * @return
     */
    @RequestMapping(value = "/queryOrder", method = RequestMethod.GET)
    public String queryOrder(@RequestParam(value = "productId") String productId) {
        return queryMap(productId);
    }

    /**
     * 模拟下单逻辑
     * ab -n 500 -c 100 http://127.0.0.1:8080/spike/orderProductMockDiffUser?productId=1
     * 存在问题（国庆活动，皮蛋粥特价，限量份100000 还剩： 99974份 该商品成功下单用户数目：26人）
     * 出现超卖现象
     * sleep时 下单数量可能比减库存数量要大
     *
     * @param productId
     * @return
     */
    @RequestMapping(value = "/orderProductMockDiffUser", method = RequestMethod.GET)
    public String orderProductMockDiffUser(@RequestParam(value = "productId") String productId) {
        // 1.查询该商品库存，为0则活动结束
        int stockNum = stock.get(productId);
        if (stockNum == 0) {
            return "活动结束";
        } else {
            // 2. 下单（模拟不同用户）
            orders.put(UUID.randomUUID().toString(), productId);

            // 3. 减库存
            stockNum = stockNum - 1;
            stock.put(productId, stockNum);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return queryMap(productId);
        }
    }

    /**
     * 模拟下单逻辑(synchronized 同步线程锁)
     * ab -n 500 -c 100 http://127.0.0.1:8080/spike/orderProductMockDiffUserBySynchronized?productId=1
     * 出现问题（速度非常的慢）
     *
     * @param productId
     * @return
     */
    @RequestMapping(value = "/orderProductMockDiffUserBySynchronized", method = RequestMethod.GET)
    public synchronized String orderProductMockDiffUserBySynchronized(@RequestParam(value = "productId") String productId) {
        // 1.查询该商品库存，为0则活动结束
        int stockNum = stock.get(productId);
        if (stockNum == 0) {
            return "活动结束";
        } else {
            // 2. 下单（模拟不同用户）
            orders.put(UUID.randomUUID().toString(), productId);

            // 3. 减库存
            stockNum = stockNum - 1;
            stock.put(productId, stockNum);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return queryMap(productId);
        }
    }

    /**
     * 模拟下单逻辑(Redis 分布式锁)
     * ab -n 500 -c 100 http://127.0.0.1:8080/spike/orderProductMockDiffUserByRedis?productId=1
     *
     * @param productId
     * @return
     */
    @RequestMapping(value = "/orderProductMockDiffUserByRedis", method = RequestMethod.GET)
    public String orderProductMockDiffUserByRedis(@RequestParam(value = "productId") String productId) {

        //超时时间，10秒
        long time = System.currentTimeMillis() + 10 * 1000;

        // 加锁
        boolean lock = this.lock(productId, String.valueOf(time));
        if (!lock) {
            System.out.println("人太多了，换个姿势试试");
            return "人太多了，换个姿势试试";
        }

        // 1.查询该商品库存，为0则活动结束
        int stockNum = stock.get(productId);
        if (stockNum == 0) {
            System.out.println("活动结束");
            return "活动结束";
        }

        // 2. 下单（模拟不同用户）
        orders.put(UUID.randomUUID().toString(), productId);

        // 3. 减库存
        stockNum = stockNum - 1;
        stock.put(productId, stockNum);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 解锁
        this.unlock(productId, String.valueOf(time));
        String queryMapStr = queryMap(productId);
        System.out.println(queryMapStr);
        return queryMapStr;
    }


}
