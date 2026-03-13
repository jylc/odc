/*
 * Copyright (c) 2023 OceanBase.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oceanbase.odc.metadb.flow;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.oceanbase.odc.core.shared.constant.FlowStatus;

/**
 * Exclude large xml column flow_config_snapshot_xml from list query.
 *
 * @author odc
 * @date 2026/3/10
 */
public class FlowInstanceViewRepositoryImpl {

    @PersistenceContext
    private EntityManager entityManager;

    public Page<FlowInstanceEntity> findAllWithoutFlowConfigSnapshot(
            Specification<FlowInstanceViewEntity> specification, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<FlowInstanceViewEntity> root = query.from(FlowInstanceViewEntity.class);

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        query.multiselect(
                root.get("id").alias("id"),
                root.get("parentInstanceId").alias("parentInstanceId"),
                root.get("projectId").alias("projectId"),
                root.get("name").alias("name"),
                root.get("flowConfigId").alias("flowConfigId"),
                root.get("creatorId").alias("creatorId"),
                root.get("organizationId").alias("organizationId"),
                root.get("processDefinitionId").alias("processDefinitionId"),
                root.get("processInstanceId").alias("processInstanceId"),
                root.get("status").alias("status"),
                root.get("description").alias("description"),
                root.get("createTime").alias("createTime"),
                root.get("updateTime").alias("updateTime"));

        List<Order> orders = toOrders(pageable.getSort(), root, cb);
        if (!orders.isEmpty()) {
            query.orderBy(orders);
        }

        TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        List<FlowInstanceEntity> content =
                typedQuery.getResultList().stream().map(this::toFlowInstanceEntity).collect(Collectors.toList());

        CriteriaQuery<Long> totalQuery = cb.createQuery(Long.class);
        Root<FlowInstanceViewEntity> totalRoot = totalQuery.from(FlowInstanceViewEntity.class);
        Predicate totalPredicate = specification == null ? null : specification.toPredicate(totalRoot, totalQuery, cb);
        if (totalPredicate != null) {
            totalQuery.where(totalPredicate);
        }
        // Keep filter predicates from specification, but avoid group/order side effects in count query.
        totalQuery.groupBy(Collections.emptyList());
        totalQuery.orderBy(Collections.emptyList());
        totalQuery.select(cb.countDistinct(totalRoot.get("id")));
        Long total = entityManager.createQuery(totalQuery).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }

    private List<Order> toOrders(Sort sort, Root<FlowInstanceViewEntity> root, CriteriaBuilder cb) {
        return sort.stream().map(order -> {
            Expression<?> expression = root.get(order.getProperty());
            return order.isAscending() ? cb.asc(expression) : cb.desc(expression);
        }).collect(Collectors.toList());
    }

    private FlowInstanceEntity toFlowInstanceEntity(Tuple tuple) {
        FlowInstanceEntity flowInstance = new FlowInstanceEntity();
        flowInstance.setId(tuple.get("id", Long.class));
        flowInstance.setParentInstanceId(tuple.get("parentInstanceId", Long.class));
        flowInstance.setProjectId(tuple.get("projectId", Long.class));
        flowInstance.setName(tuple.get("name", String.class));
        flowInstance.setFlowConfigId(tuple.get("flowConfigId", Long.class));
        flowInstance.setCreatorId(tuple.get("creatorId", Long.class));
        flowInstance.setOrganizationId(tuple.get("organizationId", Long.class));
        flowInstance.setProcessDefinitionId(tuple.get("processDefinitionId", String.class));
        flowInstance.setProcessInstanceId(tuple.get("processInstanceId", String.class));
        flowInstance.setStatus(tuple.get("status", FlowStatus.class));
        flowInstance.setDescription(tuple.get("description", String.class));
        flowInstance.setCreateTime(tuple.get("createTime", Date.class));
        flowInstance.setUpdateTime(tuple.get("updateTime", Date.class));
        return flowInstance;
    }
}
