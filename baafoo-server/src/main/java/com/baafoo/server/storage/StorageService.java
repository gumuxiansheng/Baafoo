package com.baafoo.server.storage;

/**
 * Storage service 接口，组合了所有聚合根维度的子接口。
 *
 * <p>历史上下文：此接口曾经包含 50+ 个方法，混合了 Rule、Environment、
 * Scene、RuleSet、MqRelationship、Recording、Agent、User 共 8 个聚合根。
 * P0-4 拆分后，每个聚合根的方法被提取到独立的子接口
 * （{@link RuleService}、{@link EnvironmentService}、{@link SceneService}、
 * {@link RuleSetService}、{@link MqRelationshipService}、{@link RecordingService}、
 * {@link AgentService}、{@link UserService}），本接口仅保留生命周期方法
 * （{@link #init()} / {@link #shutdown()}）并作为组合接口继承所有子接口。</p>
 *
 * <p><b>向后兼容</b>：现有依赖 {@code StorageService} 的调用方无需改动——
 * 通过接口继承，所有子接口的方法仍然可以通过 {@code StorageService} 访问。
 * 新代码应优先依赖具体的子接口（如 {@link UserService}）以减少耦合。</p>
 *
 * <p>{@link AgentRegistration} DTO 已从本接口的内部类提升为顶层类，
 * 旧代码中的 {@code StorageService.AgentRegistration} 引用需更新为
 * {@code AgentRegistration}。</p>
 */
public interface StorageService extends
        RuleService, RuleSetService, EnvironmentService, SceneService,
        RecordingService, AgentService, UserService, MqRelationshipService {

    // --- Lifecycle ---

    void init() throws Exception;

    void shutdown();
}
