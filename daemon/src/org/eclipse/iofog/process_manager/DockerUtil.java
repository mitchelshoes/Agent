/*******************************************************************************
 * Copyright (c) 2016, 2017 Iotracks, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Saeid Baghbidi
 * Kilton Hopkins
 *  Ashita Nagar
 *******************************************************************************/
package org.eclipse.iofog.process_manager;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.model.Ports.Binding;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.command.EventsResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import io.netty.util.internal.StringUtil;
import org.eclipse.iofog.element.Element;
import org.eclipse.iofog.element.ElementStatus;
import org.eclipse.iofog.element.PortMapping;
import org.eclipse.iofog.element.Registry;
import org.eclipse.iofog.status_reporter.StatusReporter;
import org.eclipse.iofog.utils.Constants;
import org.eclipse.iofog.utils.configuration.Configuration;
import org.eclipse.iofog.utils.logging.LoggingService;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.eclipse.iofog.utils.logging.LoggingService.logWarning;

/**
 * provides methods for Docker commands
 *
 * @author saeid
 */
public class DockerUtil {
	private final String MODULE_NAME = "Docker Util";

	private static DockerUtil instance;
	private DockerClient dockerClient;

	private DockerUtil() {
		initDockerClient();
	}

	public static DockerUtil getInstance() {
		if (instance == null) {
			synchronized (DockerUtil.class) {
				if (instance == null)
					instance = new DockerUtil();
			}
		}
		return instance;
	}

	/**
	 * initializes docker client
	 */
	private void initDockerClient() {
		try {
			DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()
					.withDockerHost(Configuration.getDockerUrl());
			if (!Constants.DOCKER_API_VERSION.isEmpty()) {
				configBuilder = configBuilder.withApiVersion(Constants.DOCKER_API_VERSION);
			}
			DockerClientConfig config = configBuilder.build();
			dockerClient = DockerClientBuilder.getInstance(config).build();
		} catch (Exception e) {
			LoggingService.logWarning(MODULE_NAME, "docker client initialization failed - " + e.getMessage());
			throw e;
		}
		addDockerEventHandler();
	}

	/**
	 * reinitialization of docker client
	 */
	public void reInitDockerClient() {
		try {
			if (null != dockerClient) {
				dockerClient.close();
			}
		} catch (IOException e) {
			LoggingService.logWarning(MODULE_NAME, "docker client closing failed - " + e.getMessage());
		}
		initDockerClient();
	}


	/**
	 * starts docker events handler
	 */
	private void addDockerEventHandler() {
		dockerClient.eventsCmd().exec(new EventsResultCallback() {
			@Override
			public void onNext(Event item) {
				switch (item.getType()) {
					case CONTAINER:
					case IMAGE:
						StatusReporter.setProcessManagerStatus().getElementStatus(item.getId()).setStatus(
								ElementState.fromText(item.getStatus()));
				}
			}
		});
		LoggingService.logInfo(MODULE_NAME, "docker events handler is started");
	}

	/**
	 * gets memory usage of given {@link Container}
	 *
	 * @param containerId - id of {@link Container}
	 * @return memory usage in bytes
	 */
	public long getMemoryUsage(String containerId) {
		if (!hasContainerWithContainerId(containerId))
			return 0;

		StatsCallback statsCallback = new StatsCallback();
		dockerClient.statsCmd(containerId).withContainerId(containerId).exec(statsCallback);
		while (!statsCallback.gotStats()) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException exp) {
				LoggingService.logWarning(MODULE_NAME, exp.getMessage());
			}
		}
		Map<String, Object> memoryUsage = statsCallback.getStats().getMemoryStats();
		return Long.parseLong(memoryUsage.get("usage").toString());
	}

	/**
	 * computes cpu usage of given {@link Container}
	 *
	 * @param containerId - id of {@link Container}
	 * @return a float number between 0-100
	 */
	@SuppressWarnings("unchecked")
	public float getCpuUsage(String containerId) {
		if (!hasContainerWithContainerId(containerId))
			return 0;

		StatsCallback statsCallback = new StatsCallback();
		dockerClient.statsCmd(containerId).withContainerId(containerId).exec(statsCallback);
		while (!statsCallback.gotStats()) {
			try {
				Thread.sleep(2);
			} catch (InterruptedException exp) {
				LoggingService.logWarning(MODULE_NAME, exp.getMessage());
			}
		}
		Map<String, Object> usageBefore = statsCallback.getStats().getCpuStats();
		float totalBefore = Long.parseLong(((Map<String, Object>) usageBefore.get("cpu_usage")).get("total_usage").toString());
		float systemBefore = Long.parseLong((usageBefore.get("system_cpu_usage")).toString());

		try {
			Thread.sleep(200);
		} catch (InterruptedException exp) {
			LoggingService.logWarning(MODULE_NAME, exp.getMessage());
		}

		statsCallback.reset();
		dockerClient.statsCmd(containerId).withContainerId(containerId).exec(statsCallback);
		while (!statsCallback.gotStats()) {
			try {
				Thread.sleep(2);
			} catch (InterruptedException exp) {
				LoggingService.logWarning(MODULE_NAME, exp.getMessage());
			}
		}
		Map<String, Object> usageAfter = statsCallback.getStats().getCpuStats();
		float totalAfter = Long.parseLong(((Map<String, Object>) usageAfter.get("cpu_usage")).get("total_usage").toString());
		float systemAfter = Long.parseLong((usageAfter.get("system_cpu_usage")).toString());


		return Math.abs(1000f * ((totalAfter - totalBefore) / (systemAfter - systemBefore)));
	}

	/**
	 * returns a Docker {@link Image} if exists
	 *
	 * @param imageName - name of {@link Image}
	 * @return {@link Image}
	 */
	public Image getImage(String imageName) {
		List<Image> images = dockerClient.listImagesCmd().exec();
		Optional<Image> result = images.stream()
				.filter(image -> image.getRepoTags()[0].equals(imageName)).findFirst();

		return result.orElse(null);
	}

	/**
	 * generates Docker authConfig
	 * based on Docker Remote API document
	 *
	 * @param registry - {@link Registry}
	 * @return base64 encoded string
	 */
	private String getAuth(Registry registry) {
		JsonObject auth = Json.createObjectBuilder()
				.add("username", registry.getUserName())
				.add("password", registry.getPassword())
				.add("email", registry.getUserEmail())
				.add("auth", EMPTY)
				.build();
		return Base64.getEncoder().encodeToString(auth.toString().getBytes(StandardCharsets.US_ASCII));
	}

	/**
	 * starts a {@link Container}
	 *
	 * @param id - id of {@link Container}
	 * @throws Exception
	 */
	public void startContainer(String id) throws Exception {
//		long totalMemory = ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalPhysicalMemorySize();
//		long jvmMemory = Runtime.getRuntime().maxMemory();
//		long requiredMemory = (long) Math.min(totalMemory * 0.25, 256 * Constants.MiB);
//
//		if (totalMemory - jvmMemory < requiredMemory)
//			throw new Exception("Not enough memory to start the container");

		dockerClient.startContainerCmd(id).exec();
	}

	/**
	 * stops a {@link Container}
	 *
	 * @param id - id of {@link Container}
	 * @throws Exception
	 */
	public void stopContainer(String id) throws Exception {
		dockerClient.stopContainerCmd(id).exec();
	}

	/**
	 * removes a {@link Container}
	 *
	 * @param id - id of {@link Container}
	 * @throws Exception
	 */
	public void removeContainer(String id) throws Exception {
		dockerClient.removeContainerCmd(id).withForce(true).exec();
	}

	/**
	 * gets IPv4 address of a {@link Container}
	 *
	 * @param id - id of {@link Container}
	 * @return ip address
	 * @throws Exception
	 */
	public String getContainerIpAddress(String id) throws Exception {
		try {
			InspectContainerResponse inspect = dockerClient.inspectContainerCmd(id).exec();
			return inspect.getNetworkSettings().getIpAddress();
		} catch (Exception exp) {
			logWarning(MODULE_NAME, exp.getMessage());
			throw exp;
		}
	}

	public String getContainerName(Container container) {
		return container.getNames()[0].substring(1);
	}

	/**
	 * returns a {@link Container} if exists
	 *
	 * @param elementId - name of {@link Container} (id of {@link Element})
	 * @return Optional<Container>
	 */
	public Optional<Container> getContainerByElementId(String elementId) {
		List<Container> containers = getContainers();
		return containers.stream()
				.filter(c -> getContainerName(c).equals(elementId))
				.findAny();
	}

	/**
	 * computes started time in milliseconds
	 *
	 * @param startedTime - string representing {@link Container} started time
	 * @return started time in milliseconds
	 */
	private long getStartedTime(String startedTime) {
		int milli = Integer.parseInt(startedTime.substring(20, 23));
		startedTime = startedTime.substring(0, 10) + " " + startedTime.substring(11, 19);
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		try {
			Date local = dateFormat.parse(dateFormat.format(dateFormat.parse(startedTime)));
			return local.getTime() + milli;
		} catch (Exception e) {
			return 0;
		}
	}

	/**
	 * gets {@link Container} status
	 *
	 * @param id - id of {@link Container}
	 * @return {@link ElementStatus}
	 * @throws Exception exception
	 */
	public ElementStatus getContainerStatus(String id) throws Exception {
		InspectContainerResponse inspectInfo = dockerClient.inspectContainerCmd(id).exec();
		ContainerState status = inspectInfo.getState();
		ElementStatus result = new ElementStatus();
		if (status.getRunning() != null && status.getRunning()) {
			result.setStartTime(getStartedTime(status.getStartedAt()));
			result.setCpuUsage(0);
			result.setMemoryUsage(0);
			result.setStatus(ElementState.fromText(status.getStatus()));
		} else {
			result.setStatus(ElementState.STOPPED);
		}
		return result;
	}

	/**
	 * return container last start epoch time
	 *
	 * @param id container id
	 * @return long epoch time
	 */
	public long getContainerStartedAt(String id) {
		InspectContainerResponse inspectInfo = dockerClient.inspectContainerCmd(id).exec();
		String startedAt = inspectInfo.getState().getStartedAt();
		return startedAt != null ? DateTimeFormatter.ISO_INSTANT.parse(startedAt, Instant::from).toEpochMilli() : Instant.now().toEpochMilli();
	}

	/**
	 * compares if element port mapping is equal to container port mapping
	 *
	 * @param containerId container id
	 * @param element     element
	 * @return boolean
	 */
	public boolean isPortMappingEqual(String containerId, Element element) {
		List<PortMapping> elementPorts = element.getPortMappings() != null ? element.getPortMappings() : new ArrayList<>();
		InspectContainerResponse inspectInfo = dockerClient.inspectContainerCmd(containerId).exec();
		HostConfig hostConfig = inspectInfo.getHostConfig();
		Ports ports = hostConfig.getPortBindings();
		return comparePortMapping(elementPorts, ports.getBindings());
	}

	private boolean comparePortMapping(List<PortMapping> elementPorts, Map<ExposedPort, Ports.Binding[]> portBindings) {

		List<PortMapping> containerPorts = portBindings.entrySet().stream()
				.map(entity -> {
					String exposedPort = String.valueOf(entity.getKey().getPort());
					String hostPort = entity.getValue()[0].getHostPortSpec();
					return new PortMapping(hostPort, exposedPort);
				})
				.collect(Collectors.toList());

		return elementPorts.stream()
				.allMatch(containerPorts::contains);
	}

	/**
	 * returns list of {@link Container} installed on Docker daemon
	 *
	 * @return list of {@link Container}
	 */
	public List<Container> getContainers() {
		return dockerClient.listContainersCmd().withShowAll(true).exec();
	}

	/**
	 * removes a Docker {@link Image}
	 *
	 * @param imageName - imageName of {@link Element}
	 * @throws Exception
	 */
	public void removeImage(String imageName) throws Exception {
		Image image = getImage(imageName);
		if (image == null)
			return;
		dockerClient.removeImageCmd(image.getId()).withForce(true).exec();
	}

	/**
	 * returns whether the {@link Container} exists or not
	 *
	 * @param elementId - id of {@link Element}
	 * @return boolean true if exists and false in other case
	 */
	public boolean hasContainerWithElementId(String elementId) {
		List<Container> containers = getContainers();
		Optional<Container> containerOptional = containers.stream()
				.filter(c -> getContainerName(c).equals(elementId))
				.findAny();
		return containerOptional.isPresent();
	}

	/**
	 * returns whether the {@link Container} exists or not
	 *
	 * @param containerId - id of {@link Element}
	 * @return boolean true if exists and false in other case
	 */
	public boolean hasContainerWithContainerId(String containerId) {
		List<Container> containers = getContainers();
		Optional<Container> containerOptional = containers.stream()
				.filter(container -> container.getId().equals(containerId))
				.findAny();
		return containerOptional.isPresent();
	}

	/**
	 * pulls {@link Image} from {@link Registry}
	 *
	 * @param imageName - imageName of {@link Element}
	 * @param registry  - {@link Registry} where image is placed
	 * @throws Exception
	 */
	public void pullImage(String imageName, Registry registry) throws Exception {
		String tag = null, image;
		if (imageName.contains(":")) {
			String[] sp = imageName.split(":");
			image = sp[0];
			tag = sp[1];
		} else {
			image = imageName;
		}
		PullImageCmd req = dockerClient.pullImageCmd(image).withAuthConfig(new AuthConfig()
				.withRegistryAddress(registry.getUrl())
				.withEmail(registry.getUserEmail())
				.withUsername(registry.getUserName())
				.withPassword(registry.getPassword())
		);
		if (tag != null)
			req.withTag(tag);
		PullImageResultCallback res = new PullImageResultCallback();
		res = req.exec(res);
		res.awaitSuccess();
	}

	/**
	 * creates {@link Container}
	 *
	 * @param element - {@link Element}
	 * @param host    - host ip address
	 * @return id of created {@link Container}
	 * @throws Exception
	 */
	public String createContainer(Element element, String host) throws Exception {
		RestartPolicy restartPolicy = RestartPolicy.onFailureRestart(10);

		Ports portBindings = new Ports();
		List<ExposedPort> exposedPorts = new ArrayList<>();
		if (element.getPortMappings() != null)
			element.getPortMappings().forEach(mapping -> {
				ExposedPort internal = ExposedPort.tcp(Integer.parseInt(mapping.getInside()));
				Binding external = Binding.bindPort(Integer.parseInt(mapping.getOutside()));
				portBindings.bind(internal, external);
				exposedPorts.add(internal);
			});
		List<Volume> volumes = new ArrayList<>();
		List<Bind> volumeBindings = new ArrayList<>();
		if (element.getVolumeMappings() != null) {
			element.getVolumeMappings().forEach(volumeMapping -> {
				Volume volume = new Volume(volumeMapping.getContainerDestination());
				volumes.add(volume);
				AccessMode accessMode;
				try {
					accessMode = AccessMode.valueOf(volumeMapping.getAccessMode());
				} catch (Exception e) {
					accessMode = AccessMode.DEFAULT;
				}
				volumeBindings.add(new Bind(volumeMapping.getHostDestination(), volume, accessMode));
			});
		}
		String[] extraHosts = {"iofabric:" + host, "iofog:" + host};

		Map<String, String> containerLogConfig = new HashMap<>();
		int logFiles = 1;
		if (element.getLogSize() > 2)
			logFiles = (int) (element.getLogSize() / 2);

		containerLogConfig.put("max-file", String.valueOf(logFiles));
		containerLogConfig.put("max-size", "2m");
		LogConfig containerLog = new LogConfig(LogConfig.LoggingType.DEFAULT, containerLogConfig);

		CreateContainerCmd cmd = dockerClient.createContainerCmd(element.getImageName())
				.withLogConfig(containerLog)
				.withCpusetCpus("0")
				.withExposedPorts(exposedPorts.toArray(new ExposedPort[0]))
				.withPortBindings(portBindings)
				.withEnv("SELFNAME=" + element.getElementId())
				.withName(element.getElementId())
				.withRestartPolicy(restartPolicy);
		if (element.getVolumeMappings() != null) {
			cmd = cmd
					.withVolumes(volumes.toArray(new Volume[volumes.size()]))
					.withBinds(volumeBindings.toArray(new Bind[volumeBindings.size()]));
		}
		if (StringUtil.isNullOrEmpty(host))
			cmd = cmd.withNetworkMode("host").withPrivileged(true);
		else
			cmd = cmd.withExtraHosts(extraHosts).withPrivileged(true);
		CreateContainerResponse resp = cmd.exec();
		return resp.getId();
	}
}
