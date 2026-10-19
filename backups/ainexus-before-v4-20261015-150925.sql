-- MySQL dump 10.13  Distrib 5.7.9, for Win64 (x86_64)
--
-- Host: localhost    Database: ainexus
-- ------------------------------------------------------
-- Server version	5.7.9-log

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `ainexus`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `ainexus` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin */;

USE `ainexus`;

--
-- Table structure for table `channel`
--

DROP TABLE IF EXISTS `channel`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `channel` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Human-readable channel name',
  `provider` varchar(64) COLLATE utf8mb4_bin NOT NULL DEFAULT 'custom',
  `protocol` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'openai' COMMENT '上游协议: openai=直接透传 / native=协议转换(预留)',
  `protocol_config` text COLLATE utf8mb4_bin COMMENT 'JSON protocol override: requestPath/authHeader/authPrefix/headers',
  `base_url` varchar(256) COLLATE utf8mb4_bin NOT NULL COMMENT '厂商 Base URL, 如 https://api.openai.com',
  `api_key` varchar(512) COLLATE utf8mb4_bin NOT NULL COMMENT 'Provider API key, must be encrypted in production',
  `weight` int(11) NOT NULL DEFAULT '1' COMMENT '轮询权重, 预留',
  `max_concurrent` int(11) NOT NULL DEFAULT '0' COMMENT 'Maximum in-flight requests; 0 means unlimited',
  `default_test_model` varchar(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Default public model used by management/test UI',
  `priority` int(11) NOT NULL DEFAULT '0' COMMENT '优先级: 高优先, 预留',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '1=启用 0=停用',
  `deleted` tinyint(4) NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `remark` varchar(512) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'Operator note',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_name` (`name`),
  KEY `idx_channel_enabled_priority` (`status`,`deleted`,`priority`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='AI 渠道路由表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `channel`
--

LOCK TABLES `channel` WRITE;
/*!40000 ALTER TABLE `channel` DISABLE KEYS */;
INSERT INTO `channel` VALUES (1,'openai-official','openai','openai',NULL,'https://api.openai.com','sk-xxxx',1,0,'gpt-4o',0,1,0,NULL,'2026-10-15 14:33:23','2026-10-15 14:48:42'),(2,'deepseek-official','deepseek','openai',NULL,'https://api.deepseek.com','sk-xxxx',1,0,'deepseek-chat',0,1,0,NULL,'2026-10-15 14:33:23','2026-10-15 14:48:42'),(3,'dashscope-openai-compat','qwen','openai',NULL,'https://dashscope.aliyuncs.com/compatible-mode','sk-xxxx',1,0,'qwen-max',0,1,0,NULL,'2026-10-15 14:33:23','2026-10-15 14:48:42'),(4,'BigModel-ZhiPu','zhipu','openai',NULL,'https://open.bigmodel.cn/api/paas/v4','sk-xxxx',10,50,'GLM-5.3',100,0,0,'Disabled example. Replace api_key, review protocol_config, then set status=1.','2026-10-15 14:51:13','2026-10-15 14:51:13');
/*!40000 ALTER TABLE `channel` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `channel_model`
--

DROP TABLE IF EXISTS `channel_model`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `channel_model` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `channel_id` bigint(20) NOT NULL COMMENT 'channel.id',
  `model_name` varchar(128) NOT NULL COMMENT 'Public model name accepted by gateway',
  `upstream_model_name` varchar(128) DEFAULT NULL COMMENT 'Actual provider model name; null means same as model_name',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '1=enabled, 0=disabled',
  `deleted` tinyint(4) NOT NULL DEFAULT '0' COMMENT 'Logical delete',
  `remark` varchar(512) DEFAULT NULL COMMENT 'Operator note',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_model` (`channel_id`,`model_name`),
  KEY `idx_model_enabled` (`model_name`,`status`,`deleted`),
  KEY `idx_channel_enabled` (`channel_id`,`status`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COMMENT='Models bound to upstream channels';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `channel_model`
--

LOCK TABLES `channel_model` WRITE;
/*!40000 ALTER TABLE `channel_model` DISABLE KEYS */;
INSERT INTO `channel_model` VALUES (1,1,'gpt-4o',NULL,1,0,'Migrated from channel.model_name','2026-10-15 14:33:23','2026-10-15 14:33:23'),(2,2,'deepseek-chat',NULL,1,0,'Migrated from channel.model_name','2026-10-15 14:33:23','2026-10-15 14:33:23'),(3,3,'qwen-max',NULL,1,0,'Migrated from channel.model_name','2026-10-15 14:33:23','2026-10-15 14:33:23'),(4,4,'GLM-5.3',NULL,1,0,'BigModel-ZhiPu example binding','2026-10-15 14:51:13','2026-10-15 14:51:13'),(5,4,'GLM-5.3-FLASH',NULL,1,0,'BigModel-ZhiPu example binding','2026-10-15 14:51:13','2026-10-15 14:51:13'),(6,4,'GLM-5.2',NULL,1,0,'BigModel-ZhiPu example binding','2026-10-15 14:51:13','2026-10-15 14:51:13');
/*!40000 ALTER TABLE `channel_model` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-15 15:09:25
