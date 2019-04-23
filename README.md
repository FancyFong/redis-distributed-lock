**redis锁处理并发问题**
redis锁处理高并发问题十分常见，使用的时候常见有几种错误，和对应的解决办法，在此进行自己的总结和整理。

 - set方式 
 - setnx方式
 - setnx+getset方式
 
**set方式** 
作为redis小白，一开始能想到的使用redis加锁的方式就是set。 
加锁：redis中set一个值，set（lock，1）; 
并发处理：其他线程必须拿到这个值，才可以往下进行，否则等待。

```
while(jedis.exists(lock)){
     Thread.sleep(500);
}
set(lock，1);
// 执行业务代码;
jedis.del(lock);
```

释放锁：执行完业务代码之后，释放redis锁，jedis.del(lock) 
防止死锁：set(lock,1) —>3秒后未释放，则自动释放setex(lock, 3, 1) 
**问题**：高并发情况下，进程同时获取锁状态为null，同时设置，锁之间相互覆盖，但是俩进程仍在并发执行业务代码。 

**setnx方式** 
后来发现有setnx的原子操作命令，锁存在不能设置值，返回0；锁不存在，则设置锁，返回1； 
加锁：jedis.setnx(lock, 1) 
并发处理：

```
while(jedis.setnx(lock,1)==0){
    Thread.sleep(300);
}
// 执行业务代码;
jedis.del(lock);
```
释放锁：执行完业务代码之后，释放redis锁，jedis.del(lock) 
**问题**：当进程执行出现问题，锁未释放，则其他进程永远处于阻塞状态，出现死锁。 
防止死锁：加锁时带上时间戳，setnx(lock, 时间戳+超时时间)

```
while(jedis.setnx(lock,now+超时时间)==0){
if(jedis.get(lock)<now){
      jedis.del(lock);
      jedis.setnx(lock,now+超时时间);
      break;
  }else{
      Thread.sleep(300);
  }
}
// 执行业务代码;
jedis.del(lock);
```
**问题**：当俩进程同时读到发现锁超时，都去释放锁，相互覆盖，则俩进程同时获得锁，仍并发执行业务代码。 

**setnx+getset方式** 
为解决上面的问题，可以使用getset命令，getset设置键值，并返回原来的键值。 
加锁：setnx(lock, 时间戳+超时时间) 
解决并发：

```
while(jedis.setnx(lock, now+超时时间)==0）{
    if(now>jedis.get(lock) && now>jedis.getset(lock, now+超时时间)){
        break;
    }else{
        Thread.sleep(300);
    }
}
// 执行业务代码;
jedis.del(lock);
```


**Redis分布式锁解决抢购问题**
废话不多说，首先分享一个业务场景-抢购。一个典型的高并发问题，所需的最关键字段就是库存，在高并发的情况下每次都去数据库查询显然是不合适的，因此把库存信息存入Redis中，利用redis的锁机制来控制并发访问，是一个不错的解决方案。

首先是一段业务代码：

    @Transactional
    public void orderProductMockDiffUser(String productId){
        //1.查库存
        int stockNum  = stock.get(productId);
        if(stocknum == 0){
            throw new SellException(ProductStatusEnum.STOCK_EMPTY);
            //这里抛出的异常要是运行时异常，否则无法进行数据回滚，这也是spring中比较基础的   
        }else{
            //2.下单
            orders.put(KeyUtil.genUniqueKey(),productId);//生成随机用户id模拟高并发
            sotckNum = stockNum-1;
            try{
                Thread.sleep(100);
            } catch (InterruptedExcption e){
                e.printStackTrace();
            }
            stock.put(productId,stockNum);
        }
    }

这里有一种比较简单的解决方案，就是synchronized关键字。

    public synchronized void orderProductMockDiffUser(String productId)

这就是java自带的一种锁机制，简单的对函数加锁和释放锁。但问题是这个实在是太慢了，感兴趣的可以可以写个接口用apache ab压测一下。

    ab -n 500 -c 100 http://localhost:8080/xxxxxxx

下面就是redis分布式锁的解决方法。首先要了解两个redis指令
SETNX 和 GETSET，可以在redis中文网上找到详细的介绍。
SETNX就是set if not exist的缩写，如果不存在就返回保存value并返回1，如果存在就返回0。
GETSET其实就是两个指令GET和SET，首先会GET到当前key的值并返回，然后在设置当前Key为要设置Value。

首先我们先新建一个RedisLock类：

    @Slf4j
    @Component
    public class RedisService {
        @Autowired
        private StringRedisTemplate stringRedisTemplate;
     
     
        /***
         * 加锁
         * @param key
         * @param value 当前时间+超时时间
         * @return 锁住返回true
         */
        public boolean lock(String key,String value){
            if(stringRedisTemplate.opsForValue().setIfAbsent(key,value)){//setNX 返回boolean
                return true;
            }
            //如果锁超时
            String currentValue = stringRedisTemplate.opsForValue().get(key);
            if(!StringUtils.isEmpty(currentValue) && Long.parseLong(currentValue)<System.currentTimeMillis()){
                //获取上一个锁的时间
                String oldvalue  = stringRedisTemplate.opsForValue().getAndSet(key,value);
                if(!StringUtils.isEmpty(oldvalue)&&oldvalue.equals(currentValue)){
                    return true;
                }
            }
            return false;
        }
        /***
         * 解锁
         * @param key
         * @param value
         * @return
         */
        public void unlock(String key,String value){
            try {
                String currentValue = stringRedisTemplate.opsForValue().get(key);
                if(!StringUtils.isEmpty(currentValue)&&currentValue.equals(value)){
                    stringRedisTemplate.opsForValue().getOperations().delete(key);
                }
            } catch (Exception e) {
                log.error("解锁异常");
            }
        }
    }

这个项目是springboot的项目。首先要加入redis的pom依赖，该类只有两个功能，加锁和解锁，解锁比较简单，就是删除当前key的键值对。我们主要来说一说加锁这个功能。
首先，锁的value值是当前时间加上过期时间的时间戳，Long类型。首先看到用setiFAbsent方法也就是对应的SETNX，在没有线程获得锁的情况下可以直接拿到锁，并返回true也就是加锁，最后没有获得锁的线程会返回false。 最重要的是中间对于锁超时的处理，如果没有这段代码，当秒杀方法发生异常的时候，后续的线程都无法得到锁，也就陷入了一个死锁的情况。我们可以假设CurrentValue为A，并且在执行过程中抛出了异常，这时进入了两个value为B的线程来争夺这个锁，也就是走到了注释*的地方。currentValue==A，这时某一个线程执行到了getAndSet(key,value)函数(某一时刻一定只有一个线程执行这个方法，其他要等待)。这时oldvalue也就是之前的value等于A，在方法执行过后，oldvalue会被设置为当前的value也就是B。这时继续执行，由于oldValue==currentValue所以该线程获取到锁。而另一个线程获取的oldvalue是B，而currentValue是A，所以他就获取不到锁啦。多线程还是有些乱的，需要好好想一想。

接下来就是在业务代码中加锁啦：首要要@Autowired注入刚刚RedisLock类，不要忘记对这个类加一个@Component注解否则无法注入

    private static final int TIMEOUT= 10*1000;
    @Transactional
    public void orderProductMockDiffUser(String productId){
         long time = System.currentTimeMillions()+TIMEOUT;
       if(!redislock.lock(productId,String.valueOf(time)){
        throw new SellException(101,"换个姿势再试试")
        }
        //1.查库存
        int stockNum  = stock.get(productId);
        if(stocknum == 0){
            throw new SellException(ProductStatusEnum.STOCK_EMPTY);
            //这里抛出的异常要是运行时异常，否则无法进行数据回滚，这也是spring中比较基础的   
        }else{
            //2.下单
            orders.put(KeyUtil.genUniqueKey(),productId);//生成随机用户id模拟高并发
            sotckNum = stockNum-1;
            try{
                Thread.sleep(100);
            } catch (InterruptedExcption e){
                e.printStackTrace();
            }
            stock.put(productId,stockNum);
        }
        redisLock.unlock(productId,String.valueOf(time));
    }

大功告成了！比synchronized快了不知道多少倍，再也不会被老板骂了!
