// package tributary.core.old;

// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.PutMapping;
// import org.springframework.web.bind.annotation.RequestBody;

// import tributary.api.dto.TransferAdmin;
// import tributary.core.tributaryObject.ConsumerGroup;

// public class OldAdmin {

// /**
// * Updates the admin role for a consumer group.
// *
// * @param newGroupId The identifier of the new admin consumer group.
// * @param oldGroupId The identifier of the old admin consumer group
// (optional).
// * @param password The password for verification (optional).
// */
// public void updateConsumerGroupAdmin(String newGroupId, String oldGroupId,
// String password)
// throws IllegalArgumentException {
// if (cluster.getTokenManager() == null) {
// cluster.setTokenManager(new TokenManager(password));
// }
// TokenManager tm = cluster.getTokenManager();
// ConsumerGroup<?> oldGroup = helper.getConsumerGroup(oldGroupId);

// if (oldGroup == null && tm.getAdminConsToken() != null) {
// throw new IllegalArgumentException("Admin token exists but old Admin could
// not be identified.");
// } else if (oldGroup != null && tm.getAdminConsToken() == null) {
// throw new IllegalArgumentException("Old admin token not found.");
// } else if (oldGroup != null && tm.getAdminConsToken() != null) {
// oldGroup.clearAssignments();
// oldGroup.setToken(null);
// oldGroup.rebalance();

// String token = tm.getAdminConsToken();
// if (!TokenManager.validateToken(token, oldGroup.getId(),
// oldGroup.getCreatedTime(), password)) {
// throw new IllegalArgumentException("Incorrect token for old Consumer Group
// Admin.");
// }
// }

// ConsumerGroup<?> newGroup = helper.getConsumerGroup(newGroupId);
// if (newGroup == null) {
// throw new IllegalArgumentException("New Consumer Group Admin " + newGroupId +
// " not found.");
// }

// String token = TokenManager.generateToken(newGroup.getId(),
// newGroup.getCreatedTime());
// tm.setAdminConsToken(token);
// newGroup.setToken(token);
// helper.assignTopicGeneric(newGroup);
// newGroup.rebalance();
// newGroup.showTopics();
// newGroup.showGroup();
// }

// /**
// * Updates the admin role for a producer.
// *
// * @param newProdId The identifier of the new admin producer.
// * @param oldProdId The identifier of the old admin producer (optional).
// * @param password The password for verification (optional).
// */
// public void updateProducerAdmin(String newProdId, String oldProdId, String
// password)
// throws IllegalArgumentException {
// if (cluster.getTokenManager() == null) {
// cluster.setTokenManager(new TokenManager(password));
// }
// TokenManager tm = cluster.getTokenManager();
// Producer<?> oldProd = helper.getProducer(oldProdId);

// if (oldProd != null && tm.getAdminProdToken() != null) {
// oldProd.clearAssignments();
// oldProd.setToken(null);

// String token = tm.getAdminProdToken();
// if (!TokenManager.validateToken(token, oldProd.getId(),
// oldProd.getCreatedTime(), password)) {
// throw new IllegalArgumentException("Invalid token for old Producer Admin.");
// }
// } else if (oldProd != null) {
// throw new IllegalArgumentException("Old admin token not found.");
// } else if (tm.getAdminProdToken() != null) {
// throw new IllegalArgumentException("Admin token exists but old Admin could
// not be identified.");
// }

// Producer<?> newProd = helper.getProducer(newProdId);
// if (newProd == null) {
// throw new IllegalArgumentException("New Producer Admin " + newProdId + " not
// found.");
// }

// String token = TokenManager.generateToken(newProd.getId(),
// newProd.getCreatedTime());
// tm.setAdminProdToken(token);
// newProd.setToken(token);
// helper.assignTopicGeneric(newProd);
// newProd.showTopics();
// }

// // Admin transfer endpoints but might delete later and change the Auth system
// @PutMapping("/consumerGroups/admin")
// public ResponseEntity<Void> transferGroupAdmin(@RequestBody TransferAdmin
// req) {
// controller.updateConsumerGroupAdmin(req.newId(), req.oldId(),
// req.password());
// return ResponseEntity.noContent().build();
// }

// @PutMapping("/producers/admin")
// public ResponseEntity<Void> transferProducerAdmin(@RequestBody TransferAdmin
// req) {
// controller.updateProducerAdmin(req.newId(), req.oldId(), req.password());
// return ResponseEntity.noContent().build();
// }
// }
