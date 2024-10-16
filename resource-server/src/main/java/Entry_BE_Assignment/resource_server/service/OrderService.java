package Entry_BE_Assignment.resource_server.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import Entry_BE_Assignment.resource_server.dto.OrderDto;
import Entry_BE_Assignment.resource_server.dto.OrderItemRequest;
import Entry_BE_Assignment.resource_server.dto.OrderRequest;
import Entry_BE_Assignment.resource_server.entity.Address;
import Entry_BE_Assignment.resource_server.entity.Item;
import Entry_BE_Assignment.resource_server.entity.Order;
import Entry_BE_Assignment.resource_server.entity.OrderItem;
import Entry_BE_Assignment.resource_server.enums.OrderStatus;
import Entry_BE_Assignment.resource_server.enums.Role;
import Entry_BE_Assignment.resource_server.enums.StatusCode;
import Entry_BE_Assignment.resource_server.exception.customException.BusinessException;
import Entry_BE_Assignment.resource_server.grpc.UserResponse;
import Entry_BE_Assignment.resource_server.repository.AddressRepository;
import Entry_BE_Assignment.resource_server.repository.ItemRepository;
import Entry_BE_Assignment.resource_server.repository.OrderRepository;
import Entry_BE_Assignment.resource_server.validation.OrderValidator;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

	private final OrderRepository orderRepository;
	private final AddressRepository addressRepository;
	private final ItemRepository itemRepository;
	private final OrderValidator orderValidator;
	private final PriceFactory priceFactory;

	private static final String ORDER_PREFIX = "ORD-";
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final int RANDOM_PART_LENGTH = 4;

	@Transactional
	public void createOrder(OrderRequest orderRequest, UserResponse userResponse) {

		// 구매자 권한 체크
		orderValidator.validateUserRole(userResponse.getRole(), Role.BUYER);

		// 주소 생성
		Address address = Address.createAddress(
			orderRequest.getAddress().getZipCode(),
			orderRequest.getAddress().getStreetAddress(),
			orderRequest.getAddress().getAddressDetail());

		addressRepository.save(address);

		// 주문 생성
		Order order = Order.createOrder(userResponse.getUserId(), generateUniqueOrderNumber(), address);

		for (OrderItemRequest itemRequest : orderRequest.getOrderItems()) {
			Item item = itemRepository.findById(itemRequest.getItemId())
				.orElseThrow(() -> new BusinessException(StatusCode.ITEM_NOT_FOUND));

			orderValidator.validateItemQuantity(itemRequest.getQuantity(), item.getQuantity());

			OrderItem orderItem = priceFactory.createOrderItem(order, item, itemRequest.getQuantity());
			order.addOrderItem(orderItem);

			// 각 상품의 판매 가능 수량 조정
			item.updateItemQuantity(item.getQuantity().subtract(itemRequest.getQuantity()));
		}

		int totalPrice = calculateTotalPrice(order.getOrderItems());
		order.updateTotalPrice(totalPrice);

		orderRepository.save(order);
	}

	// 주문 총 금액 계산
	private int calculateTotalPrice(List<OrderItem> orderItems) {
		return orderItems.stream()
			.map(orderItem -> new BigDecimal(
				priceFactory.calculateTotalPrice(orderItem.getItem(), orderItem.getQuantity())))
			.reduce(BigDecimal.ZERO, BigDecimal::add)
			.intValueExact();
	}

	// 주문 조회
	public List<Order> getOrdersByUser(Long userId) {
		return orderRepository.findByBuyerId(userId);
	}

	@Transactional
	public Order updateOrderStatus(Long orderId, OrderStatus newStatus, Role userRole) {
		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new BusinessException(StatusCode.ORDER_NOT_FOUND));

		if (order.getStatus() == newStatus) {
			throw new BusinessException(StatusCode.INVALID_STATUS_UPDATE);
		}

		orderValidator.validateOrderStatusTransition(order.getStatus(), newStatus, userRole);

		order.updateOrderStatus(newStatus);

		return order;
	}

	private String generateUniqueOrderNumber() {
		String datePart = LocalDate.now().format(DATE_FORMATTER);
		String randomPart = String.format("%0" + RANDOM_PART_LENGTH + "d",
			(int)(Math.random() * Math.pow(10, RANDOM_PART_LENGTH)));
		return ORDER_PREFIX + datePart + "-" + randomPart;
	}

	public OrderDto getOrderById(Long orderId, UserResponse userResponse) {
		Order order = findOrderById(orderId);
		String userId = String.valueOf(userResponse.getUserId());

		if (userId.equals(String.valueOf(order.getBuyerId()))) {
			return OrderDto.fromEntity(order);
		}

		List<OrderItem> sellerOrderItems = order.getOrderItems().stream()
			.filter(orderItem -> orderItem.getItem().getSellerId().equals(Long.parseLong(userId)))
			.collect(Collectors.toList());

		if (!sellerOrderItems.isEmpty()) {
			return OrderDto.fromEntityWithSellerItems(order, sellerOrderItems);
		}

		throw new BusinessException(StatusCode.FORBIDDEN);

	}

	// 전체 주문 목록 조회 (구매자와 판매자)
	public List<OrderDto> getAllOrders(UserResponse userResponse) {
		String userId = String.valueOf(userResponse.getUserId());

		if (userResponse.getRole().equals(String.valueOf(Role.BUYER))) {
			List<Order> byBuyerId = orderRepository.findByBuyerId(Long.parseLong(userId));
			return byBuyerId.stream().map(OrderDto::fromEntity).toList();
		}

		if (userResponse.getRole().equals(String.valueOf(Role.SELLER))) {
			List<Order> bySellerId = orderRepository.findBySellerId(Long.parseLong(userId));
			return bySellerId.stream().map(OrderDto::fromEntity).toList();
		}

		throw new BusinessException(StatusCode.FORBIDDEN);
	}

	// 주문 취소 (구매자만 가능)
	@Transactional
	public void cancelOrder(Long orderId, UserResponse userResponse) {
		if (!userResponse.getRole().equals(String.valueOf(Role.BUYER))) {
			throw new BusinessException(StatusCode.FORBIDDEN);
		}

		Order order = findOrderById(orderId);

		// 입금 전이라면 취소 가능
		if (order.getStatus() == OrderStatus.ORDER_PLACED) {
			order.updateOrderStatus(OrderStatus.ORDER_CANCELLED);
		} else if (order.getStatus() == OrderStatus.PAYMENT_RECEIVED || order.getStatus() == OrderStatus.SHIPPED) {
			// 입금 후부터는 환불 절차로 처리
			throw new BusinessException(StatusCode.REFUND_REQUIRED);
		} else {
			// 발송 완료 이후라면 반품 및 환불 절차 진행
			throw new BusinessException(StatusCode.RETURN_OR_REFUND_REQUIRED);
		}

	}

	private Order findOrderById(Long orderId) {
		return orderRepository.findById(orderId)
			.orElseThrow(() -> new BusinessException(StatusCode.ORDER_NOT_FOUND));
	}
}
