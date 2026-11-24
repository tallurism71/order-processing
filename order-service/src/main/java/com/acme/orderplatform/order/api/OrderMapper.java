package com.acme.orderplatform.order.api;

import com.acme.orderplatform.order.domain.CustomerOrder;

public final class OrderMapper {

    private OrderMapper() {
    }

    public static OrderDtos.OrderResponse toResponse(CustomerOrder o) {
        return new OrderDtos.OrderResponse(
                o.getId(), o.getCustomerId(), o.getStatus(), o.getTotalAmount(), o.getCurrency(),
                o.getFailureReason(),
                o.getLines().stream().map(l -> new OrderDtos.LineResponse(l.getSku(), l.getQuantity(), l.getUnitPrice())).toList(),
                o.getVersion(), o.getCreatedAt(), o.getUpdatedAt());
    }
}
