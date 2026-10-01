package com.github.cuixudong74.eventattributiontaggingjava.event;

/**
 * 事件在归因链路中的状态。
 */
public enum EventStatus {
    /** 已关联到已知用户且已获授权，可进入归因 */
    LINKED,
    /** 未能关联到已知用户（匿名/标识缺失），不进入归因但仍计入总量对账 */
    UNLINKED,
    /** 已关联但未获隐私授权，不进入归因但仍计入总量对账 */
    UNAUTHORIZED
}
