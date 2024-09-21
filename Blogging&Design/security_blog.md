# COMP6841 SA Project Blog
## Security Implementation Overviews
### Project Schedule
<table>
  <tr>
    <th>Week </th>
    <th>Task</th>
  </tr>
  <tr>
    <td>Week 1</td>
    <td>Come up with a clear project idea.</td>
  </tr>
  <tr>
    <td>Week 2</td>
    <td>Hone in on my idea and define clear components of the project (secure database, encryption, and RBAC).</td>
  </tr>
  <tr>
    <td>Week 3</td>
    <td>
      <ul>
        <li>Finalize architecture for integrating PostgreSQL as a secure database backend.</li>
        <li>Research and plan how to implement TLS/SSL encryption for messages.</li>
        <li>Design the RBAC system for message partitions and topics that producers and consumers have access control permissions to.</li>
      </ul>
    </td>
  </tr>
  <tr>
    <td>Week 4</td>
    <td>[What will you do in Week 4?]</td>
  </tr>
  <tr>
    <td>Week 5</td>
    <td>[What will you do in Week 5?]</td>
  </tr>
  <tr>
    <td>Week 6</td>
    <td>-- Flexibility Week --</td>
  </tr>
  <tr>
    <td>Week 7</td>
    <td>[What will you do in Week 7?]</td>
  </tr>
  <tr>
    <td>Week 8</td>
    <td>[What will you do in Week 8?]</td>
  </tr>
</table>

### Project Deliverables/Outcomes
<table>
  <tr>
    <th>Deliverable</th>
    <th>Description</th>
  </tr>
  <tr>
    <td>PostgreSQL Backend</td>
    <td>A fully functional PostgreSQL backend replacing JSON files for message (logs and records of processes) storage.</td>
  </tr>
  <tr>
    <td>TLS/SSL Encryption</td>
    <td>Implement TLS/SSL encryption for transmitting messages between producers, consumers, and the Tributary Cluster.</td>
  </tr>
  <tr>
    <td>RBAC System</td>
    <td>An access control system for regulating who can produce, consume, or replay messages based on roles and permissions.</td>
  </tr>
  <tr>
    <td>Documentation & Final Report</td>
    <td>
      Detailed documentation covering system design and security implementations for interacting with the Tributary Cluster. 
      The final report will summarize the project’s objectives, achievements, challenges, and how the security features relate to industry use, 
      emphasizing the importance of security.
    </td>
  </tr>
</table>


## Features to Implement

### PostgreSQL database

### Encryption for Data in Transit (TLS/SSL)
**Context**: Since your system relies heavily on message transmission between producers, consumers, and partitions within the Tributary Cluster, ensuring that data in transit is protected is critical. This is particularly relevant because your system emulates the behavior of Apache Kafka, which operates in environments that handle large volumes of potentially sensitive data.

**Relevance**: Adding TLS/SSL encryption ensures that messages between components (producers, consumers, topics) cannot be intercepted or tampered with while they are in transit. Given that your system supports multiple producers and consumers working concurrently, securing these communication channels protects the integrity and confidentiality of your data, preventing man-in-the-middle attacks.

**Implementation**: TLS/SSL can be layered on top of your existing network communication between producers and consumers without major architectural changes. Given your system’s focus on thread safety and concurrency, ensuring that encrypted messages are processed concurrently across threads would showcase a strong understanding of both security and software design.

### Role-Based Access Control (RBAC)
**Context**: Your Tributary Cluster manages access to topics, partitions, and message consumption by consumers. This structure lends itself well to role-based access control (RBAC), which ensures that only authorized producers or consumers can access certain topics, partitions, or message replay features.

**Relevance**: The fact that your system supports consumer groups, message replay, and partition-based message handling means that there are natural points in your architecture where access control would be essential. For example:
- Only certain producers should be able to publish to sensitive topics.
- Certain consumers should have access to replay functionality, but not everyone.
- Some partitions may store high-priority or sensitive data, requiring specific authorization.

**Implementation**: Implementing RBAC allows you to extend the flexibility of your system by adding fine-grained controls over who can produce, consume, or replay messages, aligning with the structure of your Consumer Groups and Rebalancing Strategies. This would also prevent unauthorized message consumption or accidental data access, especially during message replay or rebalancing operations.
