
## Project Overview
This Java library is based on an extremely simplified version of the event streaming infrastructure [**Apache Kafka**](https://kafka.apache.org/). A quick read of Kafka's design and purpose is recommended to understand the basis and workings of this project, a brief video to understand what Kafka is can be found [**here**](https://youtu.be/vHbvbwSEYGo). The 

Tributary Cluster is a form of Event-Driven Architecture (EDA) that uses Stream Processing. The fundamental premise on which EDA rests is the ability of producer and consumer entities in the system to share data asynchronously via a stream-like channel, which is why this project is named 'Tributary Cluster'. Therefore, stream processing allows data to move through a data pipeline to be analysed and processed asynchronously and efficiently in real-time. This is opposed to batch processing where data is collected and stored to be operated upon later. This library enhances the traditional in-memory stream EDA, which often relies on a single message queue shared by multiple consumers causing bottlenecks and delays, by incorporating elements of batch processing in certain scenarios such as replayability in event logs and delayed and parallel event consumption. By adopting a log-based approach, we overcome the limitations of message playback and memory storage seen in single message queue brokers. This improvement enhances data storage, replayability, and adds greater functionality to message processing, enabling more efficient and flexible handling of events across multiple channels.

### Breakdown of Engineering Requirements
A complete visual overview of the entire system exist, showcasing the system in the [**final UML Diagram**](Blogging&Design/UML_final_design.pdf) and a more extensive overview of the required capabilities, constrains and functionality of the system, that had been created in the beginnging of this project (so it is a little outdated) can be found in the [**Engineering Requirements**](Engineering_Requirements.md). Furthermore, a full recount of the engineering process can be found in the following links:
1. [Design Focused Implementation Blog](https://github.com/Biswas57/Tributary-Cluster/blob/main/Blogging%26Design/tributary_cluster_blog.md#tributary-cluster-design-focused-blog)
2. [Security Feature Implementation Blog](https://github.com/Biswas57/Tributary-Cluster/blob/main/Blogging%26Design/tributary_cluster_blog.md#tributary-cluster-security-implementation-blog)
3. [Reflection Blog](https://github.com/Biswas57/Tributary-Cluster/blob/main/Blogging%26Design/tributary_cluster_blog.md#reflection)

## Initialisation and Developer Use
To help other developers interact with the Tributary Cluster library, the main entry point is the `TributaryCLI` class. This class provides a command-line interface to create, modify, and interact with the tributary cluster system.

### Path to Main Function:
```
app/src/main/java/tributary/cli/TributaryCLI.java
```

### Steps to Run the TributaryCLI:

1. **Clone the Repository**:

    ```bash
    git clone https://github.com/YourUsername/Tributary-Cluster.git
    ```

2. **Navigate to the Project Directory**:

    ```bash
    cd Tributary-Cluster
    ```

3. **Build the Project**: The project uses Gradle for build automation. Build the project using:

    ```bash
    ./gradlew build
    ```

    If you're on Windows, use:

    ```cmd
    gradlew.bat build
    ```

4. **Run the CLI**: After building, you can run the `TributaryCLI` using Gradle's run task:

    ```bash
    ./gradlew run
    ```

    Alternatively, you can run the main class directly using the Java command:

    ```bash
    java -cp build/classes/java/main tributary.cli.TributaryCLI
    ```

    Ensure the classpath points to the directory where your compiled classes are located.

5. **Interact with the CLI**: Once the `TributaryCLI` is running, you can enter commands described in the follwoing section to interact with the Tributary Cluster.


### CLI Commands and Usability Testing
To run usability tests on this library I needed to develop a way to interact with tributaries, producers, and consumers via a command line interface. To do so, I coded a wrapper class called `TributaryCLI` that allows the user to input commands that create, modify, and interact with a tributary cluster system.

<table>
  <tr>
    <th><b>Command</b></th>
    <th><b>Description</b></th>
    <th><b>Output</b></th>
  </tr>
  <tr>
    <td><code>create topic &lt;id&gt; &lt;type&gt;</code></td>
    <td>
      <ul>
        <li>Creates a new topic in the tributary.</li>
        <li><code>id</code> is the topic’s identifier.</li>
        <li>
          <code>type</code> is the type of event that goes through the topic.
          While this can be any type in Java, for the purposes of the CLI it can
          either be <code>Integer</code> or <code>String</code>.
        </li>
      </ul>
    </td>
    <td>
      A message showing the id, type and other relevant information about the
      topic confirming its creation.
    </td>
  </tr>
  <tr>
    <td><code>create partition &lt;topic&gt; &lt;id&gt;</code></td>
    <td>
      <ul>
        <li>
          Creates a new partition in the topic with id <code>topic</code>.
        </li>
        <li><code>id</code> is the partition’s identifier.</li>
      </ul>
    </td>
    <td>A message confirming the partition’s creation.</td>
  </tr>
  <tr>
    <td>
      <code>create consumer group &lt;id&gt; &lt;topic&gt; &lt;rebalancing&gt;</code>
    </td>
    <td>
      <ul>
        <li>Creates a new consumer group with the given identifier.</li>
        <li>
          <code>topic</code> is the topic the consumer group is subscribed to.
        </li>
        <li>
          <code>rebalancing</code> is the consumer group’s initial rebalancing
          method, one of <code>Range</code> or <code>RoundRobin</code>.
        </li>
      </ul>
    </td>
    <td>A message confirming the consumer group’s creation.</td>
  </tr>
  <tr>
    <td><code>create consumer &lt;group&gt; &lt;id&gt;</code></td>
    <td>
      <ul>
        <li>Creates a new consumer within a consumer group.</li>
      </ul>
    </td>
    <td>A message confirming the consumer’s creation.</td>
  </tr>
  <tr>
    <td><code>delete consumer &lt;consumer&gt;</code></td>
    <td>
      <ul>
        <li>Deletes the consumer with the given id.</li>
      </ul>
    </td>
    <td>
      A message confirming the consumer’s deletion, and an output of the
      rebalanced consumer group that the consumer was previously in.
    </td>
  </tr>
  <tr>
    <td>
      <code>create producer &lt;id&gt; &lt;type&gt; &lt;allocation&gt;</code>
    </td>
    <td>
      <ul>
        <li>Creates a new producer which produces events of the given type.</li>
        <li>
          <code>allocation</code> is either <code>Random</code> or
          <code>Manual</code>, determining which method of partition selection
          is used for publishing events.
        </li>
      </ul>
    </td>
    <td>A message confirming the producer’s creation.</td>
  </tr>
  <tr>
    <td>
      <code>produce event &lt;producer&gt; &lt;topic&gt; &lt;event&gt;&lt;partition&gt;</code>
    </td>
    <td>
      <ul>
        <li>
          Produces a new event from the given producer to the given topic.
        </li>
        <li>
          How you represent the event is up to you. We recommend using a JSON
          structure to represent the different parts of an event and the
          <code>event</code> parameter to this command is a filename to a JSON
          file with the event content inside.
        </li>
        <li>
          <code>partition</code> is an optional parameter used only if the
          producer publishes events to a manually specified partition
        </li>
      </ul>
    </td>
    <td>The event id, the id of the partition it is currently in.</td>
  </tr>
  <tr>
    <td><code>consume event &lt;consumer&gt; &lt;partition&gt;</code></td>
    <td>
      <ul>
        <li>
          The given consumer consumes an event from the given partition.
          Precondition: The consumer is already allocated to the given
          partition.
        </li>
      </ul>
    </td>
    <td>
      The id and contents of the event, showing that the consumer has received
      the event.
    </td>
  </tr>
  <tr>
    <td>
      <code>consume events &lt;consumer&gt; &lt;partition&gt; &lt;number of events&gt;</code>
    </td>
    <td>
      <ul>
        <li>Consumes multiple events from the given partition.</li>
      </ul>
    </td>
    <td>The id and contents of each event received in order.</td>
  </tr>
  <tr>
    <td><code>show topic &lt;topic&gt;</code></td>
    <td>
      <ul>
        <li>Prints a visual display of the given topic, including all partitions and all of the events currently in each partition.</li>
      </ul>
    </td>
    <td>
      A detailed visual representation of the topic, listing all partitions within the topic and the events currently stored in each partition.
    </td>
  </tr>
  <tr>
    <td><code>show consumer group &lt;group&gt;</code></td>
    <td>
      <ul>
        <li>Shows all consumers in the consumer group, and which partitions each consumer is receiving events from.</li>
      </ul>
    </td>
    <td>
      A detailed list of all consumers in the specified consumer group, including the partitions each consumer is allocated to and is receiving events from.
    </td>
  </tr>
  <tr>
    <td>
      <code>parallel produce (&lt;producer&gt;, &lt;topic&gt;, &lt;event&gt;), ...</code>
    </td>
    <td>
      <ul>
        <li>
          Produces a series of events in parallel. This is purely for
          demonstrating that your tributary can cope with multiple producers
          publishing events simultaneously.
        </li>
      </ul>
    </td>
    <td>For each event, the id of the partition it is currently in.</td>
  </tr>
  <tr>
    <td>
      <code> parallel consume (&lt;consumer&gt;, &lt;partition&gt;), ... </code>
    </td>
    <td>
      <ul>
        <li>
          Consumes a series of events in parallel. This is purely for
          demonstrating that your tributary can cope with multiple consumers
          receiving events simultaneously.
        </li>
      </ul>
    </td>
    <td>For each event consumed, the contents of the event and its id.</td>
  </tr>
  <tr>
    <td>
      <code>update rebalancing method &lt;group&gt; &lt;rebalancing&gt;</code>
    </td>
    <td>
      <ul>
        <li>
          Updates the rebalancing method of consumer group <code>group</code> to
          be one of <code>Range</code> or <code>RoundRobin</code>.
        </li>
      </ul>
    </td>
    <td>A message confirming the new rebalancing method. Shows all consumers in the rebalanced consumer group, and which partitions each consumer is receiving events from.</td>
  </tr>
  <tr>
    <td>
      <code>update consumer offset &lt;consumer&gt; &lt;partition&gt; &lt;offset&gt;</code>
    </td>
    <td>
      <ul>
        <li>Plays back events for a given consumer from the offset.
        <li>Controlled Replay: Consumers can replay messages from a specific offset. ie. 2 = 2nd message in the partition</li>
        <li>Backtrack Replay: Consumers can backtrack their processed Messages, ie. -2 = 2nd last message processed</li>
        <li>If the offset is not inputted then updateConsumerOffset will set the offset as the last processed message. If the offset inputted is 0 then updateConsumerOffset will set the offset to the last message in the Partition</li>
      </ul>
    </td>
    <td>The id and contents of each event received in order.</td>
  </tr>
    <tr>
    <td>
      <code>update admin producer &lt;newAdminProducer&gt; &lt;oldAdminProducer&gt; &lt;password&gt;</code>
    </td>
    <td>
      <ul>
        <li>Transfers the admin role from the old admin producer to the new admin producer.</li>
        <li>If there is no previous admin, the old admin Producer's ID and current password are not required</li>
      </ul>
    </td>
    <td>Displays the topics and partitions the new admin producer has access to.</td>
  </tr>
  <tr>
    <td>
      <code>update admin group &lt;newAdminGroup&gt; &lt;oldAdminGroup&gt; &lt;password&gt;</code>
    </td>
    <td>
      <ul>
        <li>Transfers the admin role from the old consumer group to the new consumer group.</li>
        <li>If there is no previous admin, the old admin Consumer Group's ID and current password are not required</li>
      </ul>
    </td>
    <td>Displays the topics and partitions the new admin group has access to, and lists all consumers in the new admin group along with their assigned partitions.</td>
  </tr>
</table>
