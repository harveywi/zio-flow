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

package zio.flow.remote

import zio.flow._
import zio.schema.Schema
import zio.stream.ZNothing

class RemoteVariableSyntax[A](val self: Remote[RemoteVariableReference[A]]) extends AnyVal {
  def get(implicit schema: Schema[A]): ZFlow[Any, ZNothing, A] = self.modify((a: Remote[A]) => (a, a))

  def set(a: Remote[A])(implicit schema: Schema[A]): ZFlow[Any, ZNothing, Unit] =
    self.modify((_: Remote[A]) => ((), a))

  def modify[B](
    f: Remote[A] => (Remote[B], Remote[A])
  )(implicit schemaA: Schema[A], schemaB: Schema[B]): ZFlow[Any, ZNothing, B] =
    ZFlow.Modify(self, Remote.RemoteFunction((a: Remote[A]) => Remote.tuple2(f(a))).evaluated)

  def updateAndGet(f: Remote[A] => Remote[A])(implicit schema: Schema[A]): ZFlow[Any, ZNothing, A] =
    self.modify { (a: Remote[A]) =>
      val a2 = f(a)
      (a2, a2)
    }

  def update(f: Remote[A] => Remote[A])(implicit schema: Schema[A]): ZFlow[Any, ZNothing, Unit] =
    updateAndGet(f).unit

  def waitUntil(predicate: Remote[A] => Remote[Boolean])(implicit schema: Schema[A]): ZFlow[Any, ZNothing, Unit] =
    ZFlow.transaction[Any, ZNothing, Unit] { txn =>
      self.get.flatMap[Any, ZNothing, Unit] { v =>
        txn.retryUntil(predicate(v))
      }
    }
}
