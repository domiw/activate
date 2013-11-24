package net.fwbrasil.activate.sequence

import net.fwbrasil.activate.entity.Entity
import net.fwbrasil.activate.entity.id.UUID
import net.fwbrasil.activate.ActivateContext

abstract class Sequence[T](implicit ctx: ActivateContext) {

    val step: Int

    final def nextValue(step: Int) =
        ctx.transactional(ctx.requiresNew) {
            _nextValue
        }

    protected def _nextValue: T
}