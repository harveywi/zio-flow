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

import java.io._
import zio._
import zio.flow._
import zio.schema._

final case class DurablePromise[E, A](promiseId: String) {

  def awaitEither(implicit
    schemaE: Schema[E],
    schemaA: Schema[A]
  ): ZIO[DurableLog & ExecutionEnvironment, IOException, Either[E, A]] =
    ZIO.service[ExecutionEnvironment].flatMap { execEnv =>
      ZIO.log(s"Waiting for durable promise $promiseId") *>
        DurableLog
          .subscribe(topic(promiseId), 0L)
          .runHead
          .flatMap {
            case Some(data) =>
              ZIO.log(s"Got durable promise result for $promiseId") *>
                ZIO
                  .fromEither(execEnv.deserializer.deserialize[Either[E, A]](data))
                  .mapError(msg =>
                    new IOException(
                      s"Could not deserialize durable promise [$promiseId]: $msg (from ${new String(data.toArray)})"
                    )
                  )
            case None =>
              ZIO.fail(new IOException(s"Could not find get durable promise result [$promiseId]"))
          }
    }

  def fail(
    error: E
  )(implicit
    schemaE: Schema[E],
    schemaA: Schema[A]
  ): ZIO[DurableLog & ExecutionEnvironment, IOException, Boolean] =
    ZIO.service[ExecutionEnvironment].flatMap { execEnv =>
      ZIO.log(s"Setting $promiseId to failure $error") *>
        DurableLog.append(topic(promiseId), execEnv.serializer.serialize[Either[E, A]](Left(error))).map(_ == 0L)
    }

  def succeed(
    value: A
  )(implicit
    schemaE: Schema[E],
    schemaA: Schema[A]
  ): ZIO[DurableLog & ExecutionEnvironment, IOException, Boolean] =
    ZIO.service[ExecutionEnvironment].flatMap { execEnv =>
      ZIO.log(s"Setting $promiseId to success: $value") *>
        DurableLog.append(topic(promiseId), execEnv.serializer.serialize[Either[E, A]](Right(value))).map(_ == 0L)
    }

  private def topic(promiseId: String): String =
    s"_zflow_durable_promise_$promiseId"
}

object DurablePromise {
  implicit def schema[E, A]: Schema[DurablePromise[E, A]] = DeriveSchema.gen

  def make[E, A](promiseId: String): DurablePromise[E, A] =
    DurablePromise(promiseId)
}
