package com.acme.orderplatform.inventory.api;

import com.acme.orderplatform.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory")
public class InventoryController {

    public record RestockRequest(@Min(0) @Max(1_000_000) int available) {
    }

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/{sku}")
    public InventoryService.StockView get(@PathVariable String sku) {
        return service.getStock(sku);
    }

    @PutMapping("/{sku}")
    public InventoryService.StockView restock(@PathVariable String sku, @Valid @RequestBody RestockRequest request) {
        return service.restock(sku, request.available());
    }
}
