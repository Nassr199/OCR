# Independent check of the Kotlin Lens wire format using the official protobuf runtime + Chromium field numbers.
import sys, json
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
F = descriptor_pb2.FieldDescriptorProto
fd = descriptor_pb2.FileDescriptorProto(name='lens_test.proto', package='lens', syntax='proto2')
def msg(name, fields, enums=()):
    m = fd.message_type.add(name=name)
    for fname, num, typ, label, tname in fields:
        f = m.field.add(name=fname, number=num, type=typ, label=label)
        if tname: f.type_name = '.lens.' + tname
    return m
O, R = F.LABEL_OPTIONAL, F.LABEL_REPEATED
msg('LocaleContext', [('language',1,F.TYPE_STRING,O,None),('region',2,F.TYPE_STRING,O,None),('time_zone',3,F.TYPE_STRING,O,None)])
msg('AppliedFilter', [('filter_type',1,F.TYPE_INT32,O,None)])
msg('AppliedFilters', [('filter',1,F.TYPE_MESSAGE,R,'AppliedFilter')])
msg('RenderingContext', [('rendering_environment',2,F.TYPE_INT32,O,None)])
msg('ClientContext', [('platform',1,F.TYPE_INT32,O,None),('surface',2,F.TYPE_INT32,O,None),('locale_context',4,F.TYPE_MESSAGE,O,'LocaleContext'),
                      ('client_filters',17,F.TYPE_MESSAGE,O,'AppliedFilters'),('rendering_context',20,F.TYPE_MESSAGE,O,'RenderingContext')])
msg('RequestId', [('uuid',1,F.TYPE_UINT64,O,None),('sequence_id',2,F.TYPE_INT32,O,None),('image_sequence_id',3,F.TYPE_INT32,O,None),('time_usec',7,F.TYPE_UINT64,O,None)])
msg('RequestContext', [('request_id',3,F.TYPE_MESSAGE,O,'RequestId'),('client_context',4,F.TYPE_MESSAGE,O,'ClientContext')])
msg('ImagePayload', [('image_bytes',1,F.TYPE_BYTES,O,None)])
msg('ImageMetadata', [('width',1,F.TYPE_INT32,O,None),('height',2,F.TYPE_INT32,O,None)])
msg('ImageData', [('payload',1,F.TYPE_MESSAGE,O,'ImagePayload'),('image_metadata',3,F.TYPE_MESSAGE,O,'ImageMetadata')])
msg('ObjectsRequest', [('request_context',1,F.TYPE_MESSAGE,O,'RequestContext'),('image_data',3,F.TYPE_MESSAGE,O,'ImageData')])
msg('ServerRequest', [('objects_request',1,F.TYPE_MESSAGE,O,'ObjectsRequest')])
# response
msg('Box', [('center_x',1,F.TYPE_FLOAT,O,None),('center_y',2,F.TYPE_FLOAT,O,None),('width',3,F.TYPE_FLOAT,O,None),('height',4,F.TYPE_FLOAT,O,None),('rotation_z',5,F.TYPE_FLOAT,O,None)])
msg('Geometry', [('bounding_box',1,F.TYPE_MESSAGE,O,'Box')])
msg('Word', [('plain_text',2,F.TYPE_STRING,O,None),('text_separator',3,F.TYPE_STRING,O,None),('geometry',4,F.TYPE_MESSAGE,O,'Geometry')])
msg('Line', [('words',1,F.TYPE_MESSAGE,R,'Word'),('geometry',2,F.TYPE_MESSAGE,O,'Geometry')])
msg('Paragraph', [('lines',2,F.TYPE_MESSAGE,R,'Line'),('geometry',3,F.TYPE_MESSAGE,O,'Geometry')])
msg('TextLayout', [('paragraphs',1,F.TYPE_MESSAGE,R,'Paragraph')])
msg('Text', [('text_layout',1,F.TYPE_MESSAGE,O,'TextLayout'),('content_language',2,F.TYPE_STRING,O,None)])
msg('ObjectsResponse', [('text',3,F.TYPE_MESSAGE,O,'Text')])
msg('ServerError', [('error_type',1,F.TYPE_INT32,O,None)])
msg('ServerResponse', [('error',1,F.TYPE_MESSAGE,O,'ServerError'),('objects_response',2,F.TYPE_MESSAGE,O,'ObjectsResponse')])
pool = descriptor_pool.DescriptorPool(); pool.Add(fd)
def C(n): return message_factory.GetMessageClass(pool.FindMessageTypeByName('lens.'+n))
mode = sys.argv[1]
if mode == 'decode':
    r = C('ServerRequest')(); r.ParseFromString(open(sys.argv[2],'rb').read())
    o = r.objects_request; cc = o.request_context.client_context; rid = o.request_context.request_id
    print(json.dumps({'uuid_ok': 0 < rid.uuid < 2**63, 'seq': rid.sequence_id, 'iseq': rid.image_sequence_id, 'time_ok': rid.time_usec > 1.6e15,
        'platform': cc.platform, 'surface': cc.surface, 'lang': cc.locale_context.language, 'region': cc.locale_context.region,
        'tz': cc.locale_context.time_zone, 'filter': [f.filter_type for f in cc.client_filters.filter], 'render': cc.rendering_context.rendering_environment,
        'img_len': len(o.image_data.payload.image_bytes), 'img_head': o.image_data.payload.image_bytes[:4].hex(),
        'w': o.image_data.image_metadata.width, 'h': o.image_data.image_metadata.height,
        'reserialize_equal': r.SerializeToString()==open(sys.argv[2],'rb').read()}, ensure_ascii=False))
else:
    s = C('ServerResponse')()
    t = s.objects_response.text; t.content_language = 'ar'
    p = t.text_layout.paragraphs.add(); p.geometry.bounding_box.center_y = 0.4
    for words, cy in ([('مرحبا',' '),('يا',' '),('صديقي!','')], 0.35), ([('كيف',' '),('حالك؟','')], 0.62):
        ln = p.lines.add(); ln.geometry.bounding_box.center_x = 0.5; ln.geometry.bounding_box.center_y = cy; ln.geometry.bounding_box.width=0.8
        for w, sep in words:
            wd = ln.words.add(); wd.plain_text = w
            if sep: wd.text_separator = sep
    p2 = t.text_layout.paragraphs.add(); l2 = p2.lines.add(); w2 = l2.words.add(); w2.plain_text = 'HOTEL'
    open(sys.argv[2],'wb').write(s.SerializeToString())
    print('response written', len(s.SerializeToString()))
