import pytest
from sqlalchemy.exc import IntegrityError

from app.entities import Account


@pytest.mark.asyncio
async def test_duplicate_account_identity_rejected_by_constraint(session):
    session.add(Account(bank="AXIS", type="card", last4="1234"))
    await session.flush()
    session.add(Account(bank="AXIS", type="card", last4="1234"))
    with pytest.raises(IntegrityError):
        await session.flush()
