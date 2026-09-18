package com.travel.module.note.biz.application;

import com.travel.common.core.exception.BusinessException;
import com.travel.module.note.biz.infra.persistence.NoteDocumentMapper;
import com.travel.module.note.biz.infra.persistence.NoteDocumentPO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class NoteApplicationServiceTest {
    @Test void ownerCannotReadAnotherUsersPrivateNote() {
        NoteDocumentMapper mapper = mock(NoteDocumentMapper.class);
        NoteDocumentPO doc = new NoteDocumentPO(); doc.setId(7L); doc.setUserId("alice");
        when(mapper.selectById(7L)).thenReturn(doc);
        NoteApplicationService service = new NoteApplicationService(mapper);
        assertThrows(BusinessException.class, () -> service.getDoc(7L, "bob"));
    }

    @Test void ownerCannotUpdateAnotherUsersNote() {
        NoteDocumentMapper mapper = mock(NoteDocumentMapper.class);
        NoteDocumentPO doc = new NoteDocumentPO(); doc.setId(7L); doc.setUserId("alice");
        when(mapper.selectById(7L)).thenReturn(doc);
        NoteApplicationService service = new NoteApplicationService(mapper);
        assertThrows(BusinessException.class, () -> service.update(7L, "bob", new com.travel.module.note.biz.api.dto.NoteDocumentRequest()));
        verify(mapper, never()).updateById(any());
    }
}
