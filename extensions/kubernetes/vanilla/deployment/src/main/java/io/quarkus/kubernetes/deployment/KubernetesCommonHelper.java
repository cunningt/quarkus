
package io.quarkus.kubernetes.deployment;

import static io.dekorate.kubernetes.decorator.AddServiceResourceDecorator.distinct;
import static io.quarkus.kubernetes.deployment.Constants.DEFAULT_HTTP_PORT;
import static io.quarkus.kubernetes.deployment.Constants.HTTP_PORT;
import static io.quarkus.kubernetes.deployment.Constants.QUARKUS_ANNOTATIONS_BUILD_TIMESTAMP;
import static io.quarkus.kubernetes.deployment.Constants.QUARKUS_ANNOTATIONS_COMMIT_ID;
import static io.quarkus.kubernetes.deployment.Constants.QUARKUS_ANNOTATIONS_VCS_URL;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.dekorate.kubernetes.config.Annotation;
import io.dekorate.kubernetes.config.ConfigMapVolumeBuilder;
import io.dekorate.kubernetes.config.EnvBuilder;
import io.dekorate.kubernetes.config.MountBuilder;
import io.dekorate.kubernetes.config.Port;
import io.dekorate.kubernetes.config.PortBuilder;
import io.dekorate.kubernetes.config.SecretVolumeBuilder;
import io.dekorate.kubernetes.decorator.AddAnnotationDecorator;
import io.dekorate.kubernetes.decorator.AddAwsElasticBlockStoreVolumeDecorator;
import io.dekorate.kubernetes.decorator.AddAzureDiskVolumeDecorator;
import io.dekorate.kubernetes.decorator.AddAzureFileVolumeDecorator;
import io.dekorate.kubernetes.decorator.AddConfigMapVolumeDecorator;
import io.dekorate.kubernetes.decorator.AddEmptyDirVolumeDecorator;
import io.dekorate.kubernetes.decorator.AddEnvVarDecorator;
import io.dekorate.kubernetes.decorator.AddHostAliasesDecorator;
import io.dekorate.kubernetes.decorator.AddImagePullSecretDecorator;
import io.dekorate.kubernetes.decorator.AddInitContainerDecorator;
import io.dekorate.kubernetes.decorator.AddLabelDecorator;
import io.dekorate.kubernetes.decorator.AddLivenessProbeDecorator;
import io.dekorate.kubernetes.decorator.AddMountDecorator;
import io.dekorate.kubernetes.decorator.AddPvcVolumeDecorator;
import io.dekorate.kubernetes.decorator.AddReadinessProbeDecorator;
import io.dekorate.kubernetes.decorator.AddRoleBindingResourceDecorator;
import io.dekorate.kubernetes.decorator.AddSecretVolumeDecorator;
import io.dekorate.kubernetes.decorator.AddServiceAccountResourceDecorator;
import io.dekorate.kubernetes.decorator.ApplicationContainerDecorator;
import io.dekorate.kubernetes.decorator.ApplyArgsDecorator;
import io.dekorate.kubernetes.decorator.ApplyCommandDecorator;
import io.dekorate.kubernetes.decorator.ApplyLimitsCpuDecorator;
import io.dekorate.kubernetes.decorator.ApplyLimitsMemoryDecorator;
import io.dekorate.kubernetes.decorator.ApplyRequestsCpuDecorator;
import io.dekorate.kubernetes.decorator.ApplyRequestsMemoryDecorator;
import io.dekorate.kubernetes.decorator.ApplyWorkingDirDecorator;
import io.dekorate.kubernetes.decorator.NamedResourceDecorator;
import io.dekorate.kubernetes.decorator.RemoveAnnotationDecorator;
import io.dekorate.kubernetes.decorator.RemoveFromMatchingLabelsDecorator;
import io.dekorate.kubernetes.decorator.RemoveFromSelectorDecorator;
import io.dekorate.kubernetes.decorator.RemoveLabelDecorator;
import io.dekorate.project.BuildInfo;
import io.dekorate.project.FileProjectFactory;
import io.dekorate.project.Project;
import io.dekorate.project.ScmInfo;
import io.dekorate.utils.Annotations;
import io.dekorate.utils.Labels;
import io.dekorate.utils.Strings;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.deployment.metrics.MetricsCapabilityBuildItem;
import io.quarkus.deployment.pkg.PackageConfig;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.kubernetes.spi.CustomProjectRootBuildItem;
import io.quarkus.kubernetes.spi.DecoratorBuildItem;
import io.quarkus.kubernetes.spi.KubernetesAnnotationBuildItem;
import io.quarkus.kubernetes.spi.KubernetesCommandBuildItem;
import io.quarkus.kubernetes.spi.KubernetesHealthLivenessPathBuildItem;
import io.quarkus.kubernetes.spi.KubernetesHealthReadinessPathBuildItem;
import io.quarkus.kubernetes.spi.KubernetesInitContainerBuildItem;
import io.quarkus.kubernetes.spi.KubernetesJobBuildItem;
import io.quarkus.kubernetes.spi.KubernetesLabelBuildItem;
import io.quarkus.kubernetes.spi.KubernetesPortBuildItem;
import io.quarkus.kubernetes.spi.KubernetesProbePortNameBuildItem;
import io.quarkus.kubernetes.spi.KubernetesRoleBindingBuildItem;
import io.quarkus.kubernetes.spi.KubernetesRoleBuildItem;

public class KubernetesCommonHelper {

    private static final String ANY = null;
    private static final String OUTPUT_ARTIFACT_FORMAT = "%s%s.jar";
    private static final String[] PROMETHEUS_ANNOTATION_TARGETS = { "Service",
            "Deployment", "DeploymentConfig" };

    public static Optional<Project> createProject(ApplicationInfoBuildItem app,
            Optional<CustomProjectRootBuildItem> customProjectRoot, OutputTargetBuildItem outputTarget,
            PackageConfig packageConfig) {
        return createProject(app, customProjectRoot, outputTarget.getOutputDirectory()
                .resolve(String.format(OUTPUT_ARTIFACT_FORMAT, outputTarget.getBaseName(), packageConfig.getRunnerSuffix())));
    }

    public static Optional<Project> createProject(ApplicationInfoBuildItem app,
            Optional<CustomProjectRootBuildItem> customProjectRoot, Path artifactPath) {
        //Let dekorate create a Project instance and then override with what is found in ApplicationInfoBuildItem.
        try {
            Project project = FileProjectFactory.create(artifactPath.toFile());
            BuildInfo buildInfo = new BuildInfo(app.getName(), app.getVersion(),
                    "jar", project.getBuildInfo().getBuildTool(),
                    project.getBuildInfo().getBuildToolVersion(),
                    artifactPath.toAbsolutePath(),
                    project.getBuildInfo().getClassOutputDir(),
                    project.getBuildInfo().getResourceDir());

            return Optional
                    .of(new Project(customProjectRoot.isPresent() ? customProjectRoot.get().getRoot() : project.getRoot(),
                            buildInfo, project.getScmInfo()));

        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Creates the configurator build items.
     */
    public static Optional<Port> getPort(List<KubernetesPortBuildItem> ports, KubernetesConfig config) {
        return getPort(ports, config, config.ingress.targetPort);
    }

    /**
     * Creates the configurator build items.
     */
    public static Optional<Port> getPort(List<KubernetesPortBuildItem> ports, PlatformConfiguration config, String targetPort) {
        return combinePorts(ports, config).values().stream()
                .filter(distinct(p -> p.getName()))
                .filter(p -> p.getName().equals(targetPort))
                .findFirst();
    }

    /**
     * Creates the configurator build items.
     */
    public static Map<String, Port> combinePorts(List<KubernetesPortBuildItem> ports,
            PlatformConfiguration config) {
        Map<String, Port> allPorts = new HashMap<>();
        allPorts.putAll(verifyPorts(ports).entrySet().stream()
                .map(e -> new PortBuilder().withName(e.getKey()).withContainerPort(e.getValue()).build())
                .collect(Collectors.toMap(Port::getName, p -> p)));

        config.getPorts().entrySet().forEach(e -> {
            String name = e.getKey();
            Port configuredPort = PortConverter.convert(e);
            Port buildItemPort = allPorts.get(name);
            Port combinedPort = buildItemPort == null ? configuredPort
                    : new PortBuilder()
                            .withName(name)
                            .withHostPort(configuredPort.getHostPort() != null && configuredPort.getHostPort() != 0
                                    ? configuredPort.getHostPort()
                                    : buildItemPort.getHostPort())
                            .withContainerPort(
                                    configuredPort.getContainerPort() != null && configuredPort.getContainerPort() != 0
                                            ? configuredPort.getContainerPort()
                                            : buildItemPort.getContainerPort())
                            .withPath(Strings.isNotNullOrEmpty(configuredPort.getPath()) ? configuredPort.getPath()
                                    : buildItemPort.getPath())
                            .build();
            allPorts.put(name, combinedPort);
        });
        return allPorts;
    }

    /**
     * Creates the common decorator build items.
     */
    public static List<DecoratorBuildItem> createDecorators(Optional<Project> project, String target, String name,
            PlatformConfiguration config,
            Optional<MetricsCapabilityBuildItem> metricsConfiguration,
            List<KubernetesAnnotationBuildItem> annotations,
            List<KubernetesLabelBuildItem> labels,
            Optional<KubernetesCommandBuildItem> command,
            Optional<Port> port,
            Optional<KubernetesHealthLivenessPathBuildItem> livenessProbePath,
            Optional<KubernetesHealthReadinessPathBuildItem> readinessProbePath,
            List<KubernetesRoleBuildItem> roles,
            List<KubernetesRoleBindingBuildItem> roleBindings) {
        List<DecoratorBuildItem> result = new ArrayList<>();

        result.addAll(createLabelDecorators(project, target, name, config, labels));
        result.addAll(createAnnotationDecorators(project, target, name, config, metricsConfiguration, annotations, port));
        result.addAll(createPodDecorators(project, target, name, config));
        result.addAll(createContainerDecorators(project, target, name, config));
        result.addAll(createMountAndVolumeDecorators(project, target, name, config));
        result.addAll(createAppConfigVolumeAndEnvDecorators(project, target, name, config));

        result.addAll(createCommandDecorator(project, target, name, config, command));
        result.addAll(createArgsDecorator(project, target, name, config, command));

        //Handle Probes
        if (!port.isEmpty()) {
            result.addAll(createProbeDecorators(name, target, config.getLivenessProbe(), config.getReadinessProbe(),
                    livenessProbePath, readinessProbePath));
        }

        //Handle RBAC
        roleBindings = roleBindings.stream()
                .filter(roleBinding -> roleBinding.getTarget() == null || roleBinding.getTarget().equals(target))
                .collect(Collectors.toList());
        roles = roles.stream().filter(role -> role.getTarget() == null || role.getTarget().equals(target))
                .collect(Collectors.toList());

        if (!roleBindings.isEmpty()) {
            result.add(new DecoratorBuildItem(target, new ApplyServiceAccountNameDecorator(name, name)));
            result.add(new DecoratorBuildItem(target, new AddServiceAccountResourceDecorator(name)));
            roles.forEach(r -> result.add(new DecoratorBuildItem(target, new AddRoleResourceDecorator(name, r))));
            roleBindings.forEach(rb -> {
                String rbName = Strings.isNotNullOrEmpty(rb.getName()) ? rb.getName() : name;
                result.add(new DecoratorBuildItem(target,
                        new AddRoleBindingResourceDecorator(rbName, name, rb.getRole(),
                                rb.isClusterWide() ? AddRoleBindingResourceDecorator.RoleKind.ClusterRole
                                        : AddRoleBindingResourceDecorator.RoleKind.Role)));
                labels.forEach(l -> {
                    result.add(new DecoratorBuildItem(target,
                            new AddLabelDecorator(rb.getName(), l.getKey(), l.getValue(), "RoleBinding")));
                });
            });
        }

        return result;
    }

    private static Collection<DecoratorBuildItem> createLabelDecorators(Optional<Project> project, String target, String name,
            PlatformConfiguration config, List<KubernetesLabelBuildItem> labels) {

        List<DecoratorBuildItem> result = new ArrayList<>();
        labels.forEach(l -> {
            result.add(new DecoratorBuildItem(l.getTarget(),
                    new AddLabelDecorator(name, l.getKey(), l.getValue())));
        });

        if (!config.isAddVersionToLabelSelectors() || config.isIdempotent()) {
            result.add(new DecoratorBuildItem(target, new RemoveFromSelectorDecorator(name, Labels.VERSION)));
            result.add(new DecoratorBuildItem(target, new RemoveFromMatchingLabelsDecorator(name, Labels.VERSION)));
        }

        if (config.isIdempotent()) {
            result.add(new DecoratorBuildItem(target, new RemoveLabelDecorator(name, Labels.VERSION)));
        }

        if (!config.isAddNameToLabelSelectors()) {
            result.add(new DecoratorBuildItem(target, new RemoveLabelDecorator(name, Labels.NAME)));
            result.add(new DecoratorBuildItem(target, new RemoveFromSelectorDecorator(name, Labels.NAME)));
            result.add(new DecoratorBuildItem(target, new RemoveFromMatchingLabelsDecorator(name, Labels.NAME)));
        }

        return result;
    }

    /**
     * If user defines a custom command via configuration, this is used.
     * If not, it will use the one from other extensions.
     *
     * @param target The deployment target (e.g. kubernetes, openshift, knative)
     * @param name The name of the resource to accept the configuration
     * @param config The {@link PlatformConfiguration} instance
     * @param command Optional command item from other extensions
     */
    private static List<DecoratorBuildItem> createCommandDecorator(Optional<Project> project, String target, String name,
            PlatformConfiguration config, Optional<KubernetesCommandBuildItem> command) {
        List<DecoratorBuildItem> result = new ArrayList<>();
        if (config.getCommand().isPresent()) {
            // If command has been set in configuration, we use it
            result.add(new DecoratorBuildItem(target,
                    new ApplyCommandDecorator(name, config.getCommand().get().toArray(new String[0]))));
        } else if (command.isPresent()) {
            // If not, we use the command that has been provided in other extensions (if any).
            result.add(new DecoratorBuildItem(target,
                    new ApplyCommandDecorator(name, command.get().getCommand().toArray(new String[0]))));
        }

        return result;
    }

    /**
     * If user defines arguments via configuration, then these will be merged to the ones from other extensions.
     * If not, then only the arguments from other extensions will be used if any.
     *
     * @param target The deployment target (e.g. kubernetes, openshift, knative)
     * @param name The name of the resource to accept the configuration
     * @param config The {@link PlatformConfiguration} instance
     * @param command Optional command item from other extensions
     */
    private static List<DecoratorBuildItem> createArgsDecorator(Optional<Project> project, String target, String name,
            PlatformConfiguration config, Optional<KubernetesCommandBuildItem> command) {
        List<DecoratorBuildItem> result = new ArrayList<>();

        List<String> args = new ArrayList<>();
        command.ifPresent(cmd -> args.addAll(cmd.getArgs()));
        config.getArguments().ifPresent(args::addAll);

        if (!args.isEmpty()) {
            result.add(new DecoratorBuildItem(target, new ApplyArgsDecorator(name, args.toArray(new String[args.size()]))));
        }

        return result;
    }

    public static List<DecoratorBuildItem> createInitContainerDecorators(String target, String name,
            List<KubernetesInitContainerBuildItem> items, List<DecoratorBuildItem> decorators) {
        List<DecoratorBuildItem> result = new ArrayList<>();

        List<AddEnvVarDecorator> envVarDecorators = decorators.stream()
                .filter(d -> d.getGroup() == null || d.getGroup().equals(target))
                .map(d -> d.getDecorator(AddEnvVarDecorator.class))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        List<AddMountDecorator> mountDecorators = decorators.stream()
                .filter(d -> d.getGroup() == null || d.getGroup().equals(target))
                .map(d -> d.getDecorator(AddMountDecorator.class))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        items.stream().filter(item -> item.getTarget() == null || item.getTarget().equals(target)).forEach(item -> {
            io.dekorate.kubernetes.config.ContainerBuilder containerBuilder = new io.dekorate.kubernetes.config.ContainerBuilder()
                    .withName(item.getName())
                    .withImage(item.getImage())
                    .withCommand(item.getCommand().toArray(new String[item.getCommand().size()]))
                    .withArguments(item.getArguments().toArray(new String[item.getArguments().size()]));

            if (item.isSharedEnvironment()) {
                for (final AddEnvVarDecorator delegate : envVarDecorators) {
                    result.add(new DecoratorBuildItem(target,
                            new ApplicationContainerDecorator<ContainerBuilder>(name, item.getName()) {
                                @Override
                                public void andThenVisit(ContainerBuilder builder) {
                                    delegate.andThenVisit(builder);
                                    // Currently, we have no way to filter out provided env vars.
                                    // So, we apply them on top of every change.
                                    // This needs to be addressed in dekorate to make things more efficient
                                    for (Map.Entry<String, String> e : item.getEnvVars().entrySet()) {
                                        builder.removeMatchingFromEnv(p -> p.getName().equals(e.getKey()));
                                        builder.addNewEnv()
                                                .withName(e.getKey())
                                                .withValue(e.getValue())
                                                .endEnv();

                                    }
                                }
                            }));
                }
            }

            if (item.isSharedFilesystem()) {
                for (final AddMountDecorator delegate : mountDecorators) {
                    result.add(new DecoratorBuildItem(target,
                            new ApplicationContainerDecorator<ContainerBuilder>(target, item.getName()) {
                                @Override
                                public void andThenVisit(ContainerBuilder builder) {
                                    delegate.andThenVisit(builder);
                                }
                            }));
                }
            }

            result.add(new DecoratorBuildItem(target,
                    new AddInitContainerDecorator(name, containerBuilder
                            .addAllToEnvVars(item.getEnvVars().entrySet().stream().map(e -> new EnvBuilder()
                                    .withName(e.getKey())
                                    .withValue(e.getValue())
                                    .build()).collect(Collectors.toList()))
                            .build())));
        });
        return result;
    }

    public static List<DecoratorBuildItem> createInitJobDecorators(String target, String name,
            List<KubernetesJobBuildItem> items, List<DecoratorBuildItem> decorators) {
        List<DecoratorBuildItem> result = new ArrayList<>();

        List<AddEnvVarDecorator> envVarDecorators = decorators.stream()
                .filter(d -> d.getGroup() == null || d.getGroup().equals(target))
                .map(d -> d.getDecorator(AddEnvVarDecorator.class))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        List<NamedResourceDecorator<?>> volumeDecorators = decorators.stream()
                .filter(d -> d.getGroup() == null || d.getGroup().equals(target))
                .filter(d -> d.getDecorator() instanceof AddEmptyDirVolumeDecorator
                        || d.getDecorator() instanceof AddSecretVolumeDecorator
                        || d.getDecorator() instanceof AddEmptyDirVolumeDecorator
                        || d.getDecorator() instanceof AddAzureDiskVolumeDecorator
                        || d.getDecorator() instanceof AddAzureFileVolumeDecorator
                        || d.getDecorator() instanceof AddAwsElasticBlockStoreVolumeDecorator)
                .map(d -> (NamedResourceDecorator<?>) d.getDecorator())
                .collect(Collectors.toList());

        List<AddMountDecorator> mountDecorators = decorators.stream()
                .filter(d -> d.getGroup() == null || d.getGroup().equals(target))
                .map(d -> d.getDecorator(AddMountDecorator.class))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        items.stream().filter(item -> item.getTarget() == null || item.getTarget().equals(target)).forEach(item -> {

            result.add(new DecoratorBuildItem(target, new NamedResourceDecorator<ContainerBuilder>("Job", item.getName()) {
                @Override
                public void andThenVisit(ContainerBuilder builder, ObjectMeta meta) {
                    for (Map.Entry<String, String> e : item.getEnvVars().entrySet()) {
                        builder.removeMatchingFromEnv(p -> p.getName().equals(e.getKey()));
                        builder.addNewEnv()
                                .withName(e.getKey())
                                .withValue(e.getValue())
                                .endEnv();
                    }
                }
            }));

            if (item.isSharedEnvironment()) {
                for (final AddEnvVarDecorator delegate : envVarDecorators) {
                    result.add(
                            new DecoratorBuildItem(target, new NamedResourceDecorator<ContainerBuilder>("Job", item.getName()) {
                                @Override
                                public void andThenVisit(ContainerBuilder builder, ObjectMeta meta) {
                                    delegate.andThenVisit(builder);
                                    // Currently, we have no way to filter out provided env vars.
                                    // So, we apply them on top of every change.
                                    // This needs to be addressed in dekorate to make things more efficient
                                    for (Map.Entry<String, String> e : item.getEnvVars().entrySet()) {
                                        builder.removeMatchingFromEnv(p -> p.getName().equals(e.getKey()));
                                        builder.addNewEnv()
                                                .withName(e.getKey())
                                                .withValue(e.getValue())
                                                .endEnv();

                                    }
                                }
                            }));
                }
            }

            if (item.isSharedFilesystem()) {
                for (final NamedResourceDecorator<?> delegate : volumeDecorators) {
                    result.add(
                            new DecoratorBuildItem(target, new NamedResourceDecorator<PodSpecBuilder>("Job", item.getName()) {
                                @Override
                                public void andThenVisit(PodSpecBuilder builder, ObjectMeta meta) {
                                    delegate.visit(builder);
                                }
                            }));
                }

                for (final AddMountDecorator delegate : mountDecorators) {
                    result.add(
                            new DecoratorBuildItem(target, new NamedResourceDecorator<ContainerBuilder>("Job", item.getName()) {
                                @Override
                                public void andThenVisit(ContainerBuilder builder, ObjectMeta meta) {
                                    delegate.andThenVisit(builder);
                                }
                            }));
                }

            }
            result.add(new DecoratorBuildItem(target,
                    new AddJobResourceDecorator2(item.getName(), item.getImage(), item.getCommand(), item.getArguments())));
        });
        return result;
    }

    /**
     * Creates container decorator build items.
     *
     * @param target The deployment target (e.g. kubernetes, openshift, knative)
     * @param name The name of the resource to accept the configuration
     * @param config The {@link PlatformConfiguration} instance
     */
    private static List<DecoratorBuildItem> createContainerDecorators(Optional<Project> project, String target, String name,
            PlatformConfiguration config) {
        List<DecoratorBuildItem> result = new ArrayList<>();
        if (config.getNamespace().isPresent()) {
            result.add(new DecoratorBuildItem(target, new AddNamespaceDecorator(config.getNamespace().get())));
            result.add(new DecoratorBuildItem(target, new AddNamespaceToSubjectDecorator(config.getNamespace().get())));
        }

        config.getWorkingDir().ifPresent(w -> {
            result.add(new DecoratorBuildItem(target, new ApplyWorkingDirDecorator(name, w)));
        });

        return result;
    }

    /**
     * Creates pod decorator build items.
     *
     * @param target The deployment target (e.g. kubernetes, openshift, knative)
     * @param name The name of the resource to accept the configuration
     * @param config The {@link PlatformConfiguration} instance
     */
    private static List<DecoratorBuildItem> createPodDecorators(Optional<Project> project, String target, String name,
            PlatformConfiguration config) {
        List<DecoratorBuildItem> result = new ArrayList<>();
        config.getImagePullSecrets().ifPresent(l -> {
            l.forEach(s -> result.add(new DecoratorBuildItem(target, new AddImagePullSecretDecorator(name, s))));
        });

        config.getHostAliases().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddHostAliasesDecorator(name, HostAliasConverter.convert(e))));
        });

        config.getServiceAccount().ifPresent(s -> {
            result.add(new DecoratorBuildItem(target, new ApplyServiceAccountNameDecorator(name, s)));
        });

        config.getInitContainers().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddInitContainerDecorator(name, ContainerConverter.convert(e))));
        });

        config.getSidecars().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddSidecarDecorator(name, ContainerConverter.convert(e))));
        });

        config.getResources().limits.cpu.ifPresent(c -> {
            result.add(new DecoratorBuildItem(target, new ApplyLimitsCpuDecorator(name, c)));
        });

        config.getResources().limits.memory.ifPresent(m -> {
            result.add(new DecoratorBuildItem(target, new ApplyLimitsMemoryDecorator(name, m)));
        });

        config.getResources().requests.cpu.ifPresent(c -> {
            result.add(new DecoratorBuildItem(target, new ApplyRequestsCpuDecorator(name, c)));
        });

        config.getResources().requests.memory.ifPresent(m -> {
            result.add(new DecoratorBuildItem(target, new ApplyRequestsMemoryDecorator(name, m)));
        });

        if (config.getSecurityContext().isAnyPropertySet()) {
            result.add(new DecoratorBuildItem(target, new ApplySecuritySettingsDecorator(name, config.getSecurityContext())));
        }

        return result;
    }

    private static List<DecoratorBuildItem> createAppConfigVolumeAndEnvDecorators(Optional<Project> project, String target,
            String name,
            PlatformConfiguration config) {

        List<DecoratorBuildItem> result = new ArrayList<>();
        Set<String> paths = new HashSet<>();

        config.getAppSecret().ifPresent(s -> {
            result.add(new DecoratorBuildItem(target, new AddSecretVolumeDecorator(new SecretVolumeBuilder()
                    .withSecretName(s)
                    .withVolumeName("app-secret")
                    .build())));
            result.add(new DecoratorBuildItem(target, new AddMountDecorator(new MountBuilder()
                    .withName("app-secret")
                    .withPath("/mnt/app-secret")
                    .build())));
            paths.add("/mnt/app-secret");
        });

        config.getAppConfigMap().ifPresent(s -> {
            result.add(new DecoratorBuildItem(target, new AddConfigMapVolumeDecorator(new ConfigMapVolumeBuilder()
                    .withConfigMapName(s)
                    .withVolumeName("app-config-map")
                    .build())));
            result.add(new DecoratorBuildItem(target, new AddMountDecorator(new MountBuilder()
                    .withName("app-config-map")
                    .withPath("/mnt/app-config-map")
                    .build())));
            paths.add("/mnt/app-config-map");
        });

        if (!paths.isEmpty()) {
            result.add(new DecoratorBuildItem(target,
                    new AddEnvVarDecorator(ApplicationContainerDecorator.ANY, name, new EnvBuilder()
                            .withName("SMALLRYE_CONFIG_LOCATIONS")
                            .withValue(paths.stream().collect(Collectors.joining(",")))
                            .build())));
        }
        return result;
    }

    private static List<DecoratorBuildItem> createMountAndVolumeDecorators(Optional<Project> project, String target,
            String name,
            PlatformConfiguration config) {
        List<DecoratorBuildItem> result = new ArrayList<>();

        config.getMounts().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddMountDecorator(ANY, name, MountConverter.convert(e))));
        });

        config.getSecretVolumes().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddSecretVolumeDecorator(SecretVolumeConverter.convert(e))));
        });

        config.getConfigMapVolumes().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddConfigMapVolumeDecorator(ConfigMapVolumeConverter.convert(e))));
        });

        config.getEmptyDirVolumes().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddEmptyDirVolumeDecorator(EmptyDirVolumeConverter.convert(e))));
        });

        config.getPvcVolumes().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddPvcVolumeDecorator(PvcVolumeConverter.convert(e))));
        });

        config.getAwsElasticBlockStoreVolumes().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target,
                    new AddAwsElasticBlockStoreVolumeDecorator(AwsElasticBlockStoreVolumeConverter.convert(e))));
        });

        config.getAzureFileVolumes().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddAzureFileVolumeDecorator(AzureFileVolumeConverter.convert(e))));
        });

        config.getAzureDiskVolumes().entrySet().forEach(e -> {
            result.add(new DecoratorBuildItem(target, new AddAzureDiskVolumeDecorator(AzureDiskVolumeConverter.convert(e))));
        });
        return result;
    }

    private static List<DecoratorBuildItem> createAnnotationDecorators(Optional<Project> project, String target, String name,
            PlatformConfiguration config,
            Optional<MetricsCapabilityBuildItem> metricsConfiguration,
            List<KubernetesAnnotationBuildItem> annotations,
            Optional<Port> port) {
        List<DecoratorBuildItem> result = new ArrayList<>();

        annotations.forEach(a -> {
            result.add(new DecoratorBuildItem(a.getTarget(),
                    new AddAnnotationDecorator(name, a.getKey(), a.getValue())));
        });

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        project.ifPresent(p -> {
            ScmInfo scm = p.getScmInfo();
            String vcsUrl = scm != null ? scm.getRemote().get("origin") : null;
            String commitId = scm != null ? scm.getCommit() : null;

            // Dekorate uses its own annotations. Let's replace them with the quarkus ones.
            result.add(new DecoratorBuildItem(target, new RemoveAnnotationDecorator(Annotations.VCS_URL)));
            result.add(new DecoratorBuildItem(target, new RemoveAnnotationDecorator(Annotations.COMMIT_ID)));

            //Add quarkus vcs annotations
            if (commitId != null && !config.isIdempotent()) {
                result.add(new DecoratorBuildItem(target, new AddAnnotationDecorator(name,
                        new Annotation(QUARKUS_ANNOTATIONS_COMMIT_ID, commitId, new String[0]))));
            }
            if (vcsUrl != null) {
                result.add(new DecoratorBuildItem(target,
                        new AddAnnotationDecorator(name,
                                new Annotation(QUARKUS_ANNOTATIONS_VCS_URL, vcsUrl, new String[0]))));
            }

        });

        if (config.isAddBuildTimestamp() && !config.isIdempotent()) {
            result.add(new DecoratorBuildItem(target,
                    new AddAnnotationDecorator(name, new Annotation(QUARKUS_ANNOTATIONS_BUILD_TIMESTAMP,
                            now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd - HH:mm:ss Z")), new String[0]))));
        }

        if (config.getPrometheusConfig().annotations) {
            // Add metrics annotations
            metricsConfiguration.ifPresent(m -> {
                String path = m.metricsEndpoint();
                String prefix = config.getPrometheusConfig().prefix;
                if (port.isPresent() && path != null) {
                    result.add(new DecoratorBuildItem(target, new AddAnnotationDecorator(name,
                            config.getPrometheusConfig().scrape.orElse(prefix + "/scrape"), "true",
                            PROMETHEUS_ANNOTATION_TARGETS)));
                    result.add(new DecoratorBuildItem(target, new AddAnnotationDecorator(name,
                            config.getPrometheusConfig().path.orElse(prefix + "/path"), path, PROMETHEUS_ANNOTATION_TARGETS)));
                    result.add(new DecoratorBuildItem(target, new AddAnnotationDecorator(name,
                            config.getPrometheusConfig().port.orElse(prefix + "/port"), "" + port.get().getContainerPort(),
                            PROMETHEUS_ANNOTATION_TARGETS)));
                    result.add(new DecoratorBuildItem(target, new AddAnnotationDecorator(name,
                            config.getPrometheusConfig().scheme.orElse(prefix + "/scheme"), "http",
                            PROMETHEUS_ANNOTATION_TARGETS)));
                }
            });
        }

        //Add metrics annotations
        return result;
    }

    /**
     * Create a decorator that sets the port to the http probe.
     * The rules for setting the probe are the following:
     * 1. if 'http-action-port' is set, use that.
     * 2. if 'http-action-port-name' is set, use that to lookup the port value.
     * 3. if a `KubernetesPorbePortBuild` is set, then use that to lookup the port.
     * 4. if we still haven't found a port fallback to 8080.
     *
     * @param name The name of the deployment / container.
     * @param target The deployment target
     * @param the probe kind (e.g. readinessProbe, livenessProbe etc)
     * @param portName the probe port name build item
     * @paramt ports a list of kubernetes port build items
     * @return a decorator for configures the port of the http action of the probe.
     */
    public static DecoratorBuildItem createProbeHttpPortDecorator(String name, String target, String probeKind,
            ProbeConfig probeConfig,
            Optional<KubernetesProbePortNameBuildItem> portName,
            List<KubernetesPortBuildItem> ports) {

        //1. check if `httpActionPort` is defined
        //2. lookup port by `httpPortName`
        //3. fallback to DEFAULT_HTTP_PORT
        String httpPortName = probeConfig.httpActionPortName
                .or(() -> portName.map(KubernetesProbePortNameBuildItem::getName))
                .orElse(HTTP_PORT);

        Integer port = probeConfig.httpActionPort
                .orElse(ports.stream().filter(p -> httpPortName.equals(p.getName()))
                        .map(KubernetesPortBuildItem::getPort).findFirst().orElse(DEFAULT_HTTP_PORT));
        return new DecoratorBuildItem(target, new ApplyHttpGetActionPortDecorator(name, name, port, probeKind));
    }

    /**
     * Create the decorators needed for setting up probes.
     * The method will not create decorators related to ports, as they are not supported by all targets (e.g. knative)
     * Port related decorators are created by `applyProbePort` instead.
     *
     * @return a list of decorators that configure the probes
     */
    private static List<DecoratorBuildItem> createProbeDecorators(String name, String target, ProbeConfig livenessProbe,
            ProbeConfig readinessProbe,
            Optional<KubernetesHealthLivenessPathBuildItem> livenessPath,
            Optional<KubernetesHealthReadinessPathBuildItem> readinessPath) {
        List<DecoratorBuildItem> result = new ArrayList<>();
        createLivenessProbe(name, target, livenessProbe, livenessPath).ifPresent(d -> result.add(d));
        createReadinessProbe(name, target, readinessProbe, readinessPath).ifPresent(d -> result.add(d));
        return result;
    }

    private static Optional<DecoratorBuildItem> createLivenessProbe(String name, String target, ProbeConfig livenessProbe,
            Optional<KubernetesHealthLivenessPathBuildItem> livenessPath) {
        if (livenessProbe.hasUserSuppliedAction()) {
            return Optional.of(
                    new DecoratorBuildItem(target, new AddLivenessProbeDecorator(name, ProbeConverter.convert(livenessProbe))));
        } else if (livenessPath.isPresent()) {
            return Optional.of(new DecoratorBuildItem(target, new AddLivenessProbeDecorator(name,
                    ProbeConverter.builder(livenessProbe).withHttpActionPath(livenessPath.get().getPath()).build())));
        }
        return Optional.empty();
    }

    private static Optional<DecoratorBuildItem> createReadinessProbe(String name, String target, ProbeConfig readinessProbe,
            Optional<KubernetesHealthReadinessPathBuildItem> readinessPath) {
        if (readinessProbe.hasUserSuppliedAction()) {
            return Optional.of(new DecoratorBuildItem(target,
                    new AddReadinessProbeDecorator(name, ProbeConverter.convert(readinessProbe))));
        } else if (readinessPath.isPresent()) {
            return Optional.of(new DecoratorBuildItem(target, new AddReadinessProbeDecorator(name,
                    ProbeConverter.builder(readinessProbe).withHttpActionPath(readinessPath.get().getPath()).build())));
        }
        return Optional.empty();
    }

    private static Map<String, Integer> verifyPorts(List<KubernetesPortBuildItem> kubernetesPortBuildItems) {
        final Map<String, Integer> result = new HashMap<>();
        final Set<Integer> usedPorts = new HashSet<>();
        for (KubernetesPortBuildItem entry : kubernetesPortBuildItems) {
            final String name = entry.getName();
            if (result.containsKey(name)) {
                throw new IllegalArgumentException(
                        "All Kubernetes ports must have unique names - " + name + "has been used multiple times");
            }
            final Integer port = entry.getPort();
            if (usedPorts.contains(port)) {
                throw new IllegalArgumentException(
                        "All Kubernetes ports must be unique - " + port + "has been used multiple times");
            }
            result.put(name, port);
            usedPorts.add(port);
        }
        return result;
    }
}
