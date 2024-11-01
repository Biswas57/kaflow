# COMP6841 SA Project Blog
[How this Event Stream-Processing System was designed.](../Engineering_Requirements.md)
## Background

There are a few major security aspects to take into consideration when analyzing the soundness and reliability of a stream processing application.

**Access Control:** While it’s easy to see why access control is important in any system, it’s particularly important when being granted access means gaining a level of control over critical data. This is a scenario not uncommon when dealing with big data processors such as that involved in stream processing applications. Let’s consider an organization working with Apache Kafka. Such an organization will have developed producers and consumers that communicate with a Kafka cluster on a continuous basis.Without access controls in place, any client could be configured to read from or write to any particular topic within the Kafka system. As your organization matures and begins to utilize the Kafka cluster on a larger scale and for various types of data (some more important than others), it’s likely that a fly-by-night approach to securing the implementation of the platform will not suffice. In this instance, it’s critical that all clients attempting to read or write to the cluster be properly authenticated and authorized for the topics with which they are attempting to interact, to ensure that improper access to potentially sensitive data is prevented. The focus is to utilise access control lists (ACLs) to authorize particular applications to read from or write to particular topics – thus providing secure access on a more granular level.

**Data Security:** Just as authentication and authorization are critical security aspects to consider when dealing in stream processing, ensuring the security of the data as it is being transmitted over the network by such applications is also of the utmost importance. Continuing to use Kafka as our example platform, data-in-transit via write operations from producers to Kafka brokers and via read operations executed by consumers against these Kafka brokers, should be protected from undue access by unauthorized systems. In an effort to provide a more thorough security policy for such a stream processing implementation, this data should be encrypted when it is communicated between client applications and Kafka.

## Tributary Security Implementation
### Project Schedule and Overview
<table>
  <tr>
    <th>Term Progression</th>
    <th>Task</th>
  </tr>
  <tr>
    <td>Week 1</td>
    <td>Come up with a clear project idea.</td>
  </tr>
  <tr>
    <td>Week 2</td>
    <td>Hone in on my idea and define clear components of the project (secure database, and RBAC).</td>
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
The Tributary Cluster currently stores data using JSON files, which lack scalability, security, and efficiency for large-scale operations. Replacing the JSON-based storage with a PostgreSQL database will provide a more secure, reliable, and scalable solution for managing messages, topics, partitions, producers, and consumers.

### Encryption for Data in Transit (TLS/SSL)
Since the Kafka system relies heavily on message transmission between producers, consumers, and partitions within the Tributary Cluster, ensuring that data in transit is protected is critical. This is particularly relevant because the system operates in environments that handle large volumes of potentially sensitive data. So Adding TLS/SSL encryption ensures that messages between components (producers, consumers, topics) cannot be intercepted or tampered with while they are in transit. Given that the system supports multiple producers and consumers working concurrently, securing these communication channels protects the integrity and confidentiality of your data, preventing man-in-the-middle attacks.

To implement this, TLS/SSL can be layered on top of your existing network communication between producers and consumers without major architectural changes. Given your system’s focus on thread safety and concurrency, ensuring that encrypted messages are processed concurrently across threads would showcase a strong understanding of both security and software design.

### Role-Based Access Control (RBAC)
Tributary Cluster manages access to topics, partitions, and message consumption by consumers. This structure lends itself well to role-based access control (RBAC), which ensures that only authorized producers or consumers can access certain topics, partitions, or message replay features. The fact that my system supports consumer groups, message replay, and partition-based message handling means that there are natural points in the architecture where access control would be essential. For example:
- Only certain producers should be able to publish to sensitive topics.
- Certain consumers should have access to replay functionality, but not everyone.
- Some partitions may store high-priority or sensitive data, requiring specific authorization.

Implementing RBAC allows us to extend the system's flexibility by adding fine-grained controls over who can produce, consume, or replay messages, aligning with the structure of your Consumer Groups and Rebalancing Strategies. This would also prevent unauthorized message consumption or accidental data access, especially during message replay or rebalancing operations.

## Progress Blog
Things to implement:

**RBAC**
- Change Producers and Consumer Groups to be outside of Cluster (later)
- Change Cluster from Singleton pattern so multiple can exist (later)
- Make offset property of the partition and not the consumer because partitions assigned to certain consumers can change ✅
- Implement admin Producer and Admin Consumer Group, which allows us to access any partition / topic ✅
  - Tokenization for Admin Producer and Admin Consumer Group ✅
  - Implement verify produce and consume functions ✅
  - Add message log for Admin Producer and Admin Consumer Group for added security incase of a breach.
  - Implement a mechanism that locks an account or restricts access after a certain number of failed login attempts to prevent brute force attacks.
- RBAC through error messages. ✅
- Make usability test SETUP for multiple topics, partitions, producers and consumer groups, so I don't waste time in the demonstration ✅

**Encryption**
- Encryption for Message Production and Decryption for message consumption ✅
- Add bytes as a form of payload for messages -> can simulate breaking the encryption using buffer overflow or any other attack ✅
- Use RSA for encryption and decryption ✅
- **Optional:** Create Keys for each message to consumer group or producer, can work well for RBAC ✅
