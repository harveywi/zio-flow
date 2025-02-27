/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.internal

import java.io.IOException

import zio._
import zio.stream._

trait IndexedStore {
  def position(topic: String): IO[IOException, Long]
  def put(topic: String, value: Chunk[Byte]): IO[IOException, Long]
  def scan(topic: String, position: Long, until: Long): ZStream[Any, IOException, Chunk[Byte]]
}

object IndexedStore {

  def position(topic: String): ZIO[IndexedStore, IOException, Long] =
    ZIO.serviceWithZIO(_.position(topic))

  def put(topic: String, value: Chunk[Byte]): ZIO[IndexedStore, IOException, Long] =
    ZIO.serviceWithZIO(_.put(topic, value))

  def scan(topic: String, position: Long, until: Long): ZStream[IndexedStore, IOException, Chunk[Byte]] =
    ZStream.serviceWithStream(_.scan(topic, position, until))

  val inMemory: ZLayer[Any, Nothing, IndexedStore] =
    ZLayer {
      for {
        topics <- Ref.make[Map[String, Chunk[Chunk[Byte]]]](Map.empty)
      } yield InMemoryIndexedStore(topics)
    }

  private final case class InMemoryIndexedStore(topics: Ref[Map[String, Chunk[Chunk[Byte]]]]) extends IndexedStore {

    def position(topic: String): IO[IOException, Long] =
      topics.get.map { topics =>
        topics.get(topic) match {
          case Some(values) => values.length.toLong
          case None         => 0L
        }
      }

    def put(topic: String, value: Chunk[Byte]): IO[IOException, Long] =
      topics.modify { topics =>
        topics.get(topic) match {
          case Some(values) => values.length.toLong -> topics.updated(topic, values :+ value)
          case None         => 0L                   -> topics.updated(topic, Chunk(value))
        }
      }

    def scan(topic: String, position: Long, until: Long): ZStream[Any, IOException, Chunk[Byte]] =
      ZStream.unwrap {
        topics.get.map { topics =>
          topics.get(topic) match {
            case Some(values) => ZStream.fromIterable(values.slice(position.toInt, until.toInt))
            case None         => ZStream.empty
          }
        }
      }
  }
}
