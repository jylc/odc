CREATE TABLE `${const:com.oceanbase.odc.plugin.task.obmysql.partitionplan.OBMySQLSqlExprPartitionExprGeneratorTest.RANGE_TABLE_NAME}` (
  `id` bigint(20) unsigned NOT NULL,
  `gmt_create` datetime NOT NULL,
  `gmt_modified` datetime NOT NULL,
  `datekey` int(11) NOT NULL,
  `amap_order_id` varchar(100) NOT NULL,
  `amap_pay_no` varchar(100) NOT NULL,
  `trade_no` varchar(100) NOT NULL,
  `batch_no` varchar(100) DEFAULT NULL,
  `fee` decimal(13,2) DEFAULT NULL,
  `trade_type` varchar(100) NOT NULL,
  `amap_fee` decimal(13,2) DEFAULT NULL,
  `status` int(11) DEFAULT NULL,
  `reason` varchar(1024) DEFAULT NULL,
  `pay_channel` varchar(32) DEFAULT NULL,
  `sub_channel` varchar(32) DEFAULT NULL,
  `trade_time` datetime DEFAULT NULL,
  `clearing_amount` decimal(13,2) DEFAULT NULL,
  `service_fee` decimal(13,2) DEFAULT NULL,
  `merchant_id` varchar(32) DEFAULT NULL,
  `org_id` varchar(32) DEFAULT NULL
) partition by range columns(datekey)
(partition p20220829 values less than (20220830),
partition p20220830 values less than (20220831),
partition p20220831 values less than (20220901));