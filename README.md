# redis-distributed-lock
redis 分布式锁实例
主题：对于传统的synchronized同步方案来说，redis分布式锁更加高效。
以商品秒杀为例，当我们要执行以下方法秒杀时：
synchronized只能允许单线程执行该方法，但是对于不同的商品来说，没不要单线程执行，只要针对不同的productId单线程执行该方法即可。因此需要分布式锁。
