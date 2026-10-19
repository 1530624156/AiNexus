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
  `model_name` varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT '对外暴露的模型名(请求体 model 字段)',
  `channel_name` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '渠道名(同 model 多渠道容灾)',
  `protocol` varchar(32) COLLATE utf8mb4_bin NOT NULL DEFAULT 'openai' COMMENT '上游协议: openai=直接透传 / native=协议转换(预留)',
  `base_url` varchar(256) COLLATE utf8mb4_bin NOT NULL COMMENT '厂商 Base URL, 如 https://api.openai.com',
  `api_key` varchar(256) COLLATE utf8mb4_bin NOT NULL COMMENT '厂商真实 API Key(替换进 Authorization 头)',
  `weight` int(11) NOT NULL DEFAULT '1' COMMENT '轮询权重, 预留',
  `priority` int(11) NOT NULL DEFAULT '0' COMMENT '优先级: 高优先, 预留',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '1=启用 0=停用',
  `deleted` tinyint(4) NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_model_status` (`model_name`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='AI 渠道路由表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `channel`
--

LOCK TABLES `channel` WRITE;
/*!40000 ALTER TABLE `channel` DISABLE KEYS */;
INSERT INTO `channel` VALUES (1,'gpt-4o','openai-official','openai','https://api.openai.com','sk-xxxx',1,0,1,0,'2026-10-15 14:33:23','2026-10-15 14:33:23'),(2,'deepseek-chat','deepseek-official','openai','https://api.deepseek.com','sk-xxxx',1,0,1,0,'2026-10-15 14:33:23','2026-10-15 14:33:23'),(3,'qwen-max','dashscope-openai-compat','openai','https://dashscope.aliyuncs.com/compatible-mode','sk-xxxx',1,0,1,0,'2026-10-15 14:33:23','2026-10-15 14:33:23');
/*!40000 ALTER TABLE `channel` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping events for database 'ainexus'
--

--
-- Dumping routines for database 'ainexus'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-15 14:48:26
