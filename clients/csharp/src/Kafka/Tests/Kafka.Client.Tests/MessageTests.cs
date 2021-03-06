﻿/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace Kafka.Client.Tests
{
    using System;
    using System.IO;
    using System.Linq;
    using System.Text;
    using Kafka.Client.Messages;
    using Kafka.Client.Utils;
    using NUnit.Framework;

    /// <summary>
    /// Tests for the <see cref="Message"/> class.
    /// </summary>
    [TestFixture]
    public class MessageTests
    {
        private readonly int ChecksumPartLength = 4;

        private readonly int MagicNumberPartOffset = 0;
        private readonly int ChecksumPartOffset = 2;
        private readonly int DataPartOffset = 6;

        /// <summary>
        /// Demonstrates a properly parsed message.
        /// </summary>
        [Test]
        public void ParseFromValid()
        {
            Crc32Hasher crc32 = new Crc32Hasher();

            string payload = "kafka";
            byte magic = 1;
            byte attributes = 0;
            byte[] payloadData = Encoding.UTF8.GetBytes(payload);
            byte[] payloadSize = BitConverter.GetBytes(payloadData.Length);
            byte[] checksum = crc32.ComputeHash(payloadData);
            byte[] messageData = new byte[payloadData.Length + 2 + payloadSize.Length + checksum.Length];

            Buffer.BlockCopy(payloadSize, 0, messageData, 0, payloadSize.Length);
            messageData[4] = magic;
            messageData[5] = attributes;
            Buffer.BlockCopy(checksum, 0, messageData, payloadSize.Length + 2, checksum.Length);
            Buffer.BlockCopy(payloadData, 0, messageData, payloadSize.Length + 2 + checksum.Length, payloadData.Length);

            Message message = Message.ParseFrom(messageData);

            Assert.IsNotNull(message);
            Assert.AreEqual(magic, message.Magic);
            Assert.IsTrue(payloadData.SequenceEqual(message.Payload));
            Assert.IsTrue(checksum.SequenceEqual(message.Checksum));
        }

        /// <summary>
        /// Ensure that the bytes returned from the message are in valid kafka sequence.
        /// </summary>
        [Test]
        public void GetBytesValidSequence()
        {
            Message message = new Message(new byte[10], CompressionCodecs.NoCompressionCodec);

            MemoryStream ms = new MemoryStream();
            message.WriteTo(ms);

            // len(payload) + 1 + 4
            Assert.AreEqual(16, ms.Length);

            // first 4 bytes = the magic number
            Assert.AreEqual((byte)1, ms.ToArray()[0]);

            // attributes
            Assert.AreEqual((byte)0, ms.ToArray()[1]);

            // next 4 bytes = the checksum
            Assert.IsTrue(message.Checksum.SequenceEqual(ms.ToArray().Skip(2).Take(4).ToArray<byte>()));

            // remaining bytes = the payload
            Assert.AreEqual(10, ms.ToArray().Skip(6).ToArray<byte>().Length);
        }

        [Test]
        public void WriteToValidSequenceForDefaultConstructor()
        {
            byte[] messageBytes = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 };
            Message message = new Message(messageBytes);
            MemoryStream ms = new MemoryStream();
            message.WriteTo(ms);

            Assert.AreEqual(1, ms.ToArray()[MagicNumberPartOffset]);    // default magic number should be 1

            byte[] checksumPart = new byte[ChecksumPartLength];
            Array.Copy(ms.ToArray(), ChecksumPartOffset, checksumPart, 0, ChecksumPartLength);
            Assert.AreEqual(Crc32Hasher.Compute(messageBytes), checksumPart);

            message.ToString();

            byte[] dataPart = new byte[messageBytes.Length];
            Array.Copy(ms.ToArray(), DataPartOffset, dataPart, 0, messageBytes.Length);
            Assert.AreEqual(messageBytes, dataPart);
        }

        [Test]
        public void WriteToValidSequenceForCustomConstructor()
        {
            byte[] messageBytes = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 };
            byte[] customChecksum = new byte[] { 3, 4, 5, 6 };
            Message message = new Message(messageBytes, customChecksum);
            MemoryStream ms = new MemoryStream();
            message.WriteTo(ms);

            Assert.AreEqual((byte)1, ms.ToArray()[MagicNumberPartOffset]);

            byte[] checksumPart = new byte[ChecksumPartLength];
            Array.Copy(ms.ToArray(), ChecksumPartOffset, checksumPart, 0, ChecksumPartLength);
            Assert.AreEqual(customChecksum, checksumPart);

            byte[] dataPart = new byte[messageBytes.Length];
            Array.Copy(ms.ToArray(), DataPartOffset, dataPart, 0, messageBytes.Length);
            Assert.AreEqual(messageBytes, dataPart);
        }
    }
}
